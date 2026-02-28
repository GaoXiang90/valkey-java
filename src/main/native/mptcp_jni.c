#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>

/* IPPROTO_MPTCP was standardised in Linux 5.6; provide a fallback for older
 * kernel headers that were in use at compile time. */
#ifndef IPPROTO_MPTCP
#define IPPROTO_MPTCP 262
#endif

static void throw_sockex(JNIEnv *env, const char *msg) {
    jclass c = (*env)->FindClass(env, "java/net/SocketException");
    if (c) (*env)->ThrowNew(env, c, msg);
}

static int mptcp_enabled_value(void) {
#ifdef __linux__
    FILE *f = fopen("/proc/sys/net/mptcp/enabled", "r");
    if (!f) return 0;
    char buf[32] = {0};
    if (!fgets(buf, sizeof(buf), f)) { fclose(f); return 0; }
    fclose(f);
    int v = 0;
    for (char *p = buf; *p >= '0' && *p <= '9'; ++p)
        v = v * 10 + (*p - '0');
    return v;
#else
    return 0;
#endif
}

static void copy_sockopts_and_flags(int fromfd, int tofd) {
    socklen_t len; int v;
#ifdef SO_REUSEADDR
    len = sizeof(v);
    if (!getsockopt(fromfd, SOL_SOCKET, SO_REUSEADDR, &v, &len))
        setsockopt(tofd, SOL_SOCKET, SO_REUSEADDR, &v, len);
#endif
#ifdef SO_REUSEPORT
    len = sizeof(v);
    if (!getsockopt(fromfd, SOL_SOCKET, SO_REUSEPORT, &v, &len))
        setsockopt(tofd, SOL_SOCKET, SO_REUSEPORT, &v, len);
#endif
    len = sizeof(v);
    if (!getsockopt(fromfd, SOL_SOCKET, SO_KEEPALIVE, &v, &len))
        setsockopt(tofd, SOL_SOCKET, SO_KEEPALIVE, &v, len);
    len = sizeof(v);
    if (!getsockopt(fromfd, IPPROTO_TCP, TCP_NODELAY, &v, &len))
        setsockopt(tofd, IPPROTO_TCP, TCP_NODELAY, &v, len);
    struct linger lg; len = sizeof(lg);
    if (!getsockopt(fromfd, SOL_SOCKET, SO_LINGER, &lg, &len))
        setsockopt(tofd, SOL_SOCKET, SO_LINGER, &lg, len);
    /* Preserve O_NONBLOCK and FD_CLOEXEC */
    int fl = fcntl(fromfd, F_GETFL);   if (fl != -1)   fcntl(tofd, F_SETFL, fl);
    int fdfl = fcntl(fromfd, F_GETFD);  if (fdfl != -1) fcntl(tofd, F_SETFD, fdfl);
}

static int extract_fd_from_socket(JNIEnv *env, jobject socket) {
    jclass socketCls = (*env)->GetObjectClass(env, socket);
    jfieldID implFid = (*env)->GetFieldID(env, socketCls, "impl", "Ljava/net/SocketImpl;");
    if (!implFid) {
        (*env)->ExceptionClear(env);
        throw_sockex(env, "Cannot find Socket.impl field");
        return -1;
    }
    jobject impl = (*env)->GetObjectField(env, socket, implFid);
    if (!impl) {
        throw_sockex(env, "Socket.impl is null — socket not initialized");
        return -1;
    }

    jclass delegCls = (*env)->FindClass(env, "java/net/DelegatingSocketImpl");
    if ((*env)->ExceptionCheck(env)) {
        /* Class not found on pre-Java-15 runtimes — nothing to unwrap. */
        (*env)->ExceptionClear(env);
    } else if (delegCls && (*env)->IsInstanceOf(env, impl, delegCls)) {
        jfieldID delegFid = (*env)->GetFieldID(env, delegCls, "delegate", "Ljava/net/SocketImpl;");
        if (delegFid) {
            jobject delegate = (*env)->GetObjectField(env, impl, delegFid);
            if (delegate) impl = delegate;
        } else {
            (*env)->ExceptionClear(env);
        }
    }

    jclass implCls = (*env)->FindClass(env, "java/net/SocketImpl");
    if (!implCls) {
        (*env)->ExceptionClear(env);
        throw_sockex(env, "Cannot find java.net.SocketImpl class");
        return -1;
    }
    jfieldID fdObjFid = (*env)->GetFieldID(env, implCls, "fd", "Ljava/io/FileDescriptor;");
    if (!fdObjFid) {
        (*env)->ExceptionClear(env);
        throw_sockex(env, "Cannot find SocketImpl.fd field");
        return -1;
    }
    jobject fdObj = (*env)->GetObjectField(env, impl, fdObjFid);
    if (!fdObj) {
        throw_sockex(env, "SocketImpl.fd is null — socket not created");
        return -1;
    }

    jclass fdCls = (*env)->GetObjectClass(env, fdObj);
    jfieldID fdIntFid = (*env)->GetFieldID(env, fdCls, "fd", "I");
    if (!fdIntFid) {
        (*env)->ExceptionClear(env);
        throw_sockex(env, "Cannot find FileDescriptor.fd field");
        return -1;
    }
    int fd = (*env)->GetIntField(env, fdObj, fdIntFid);
    if (fd < 0) {
        throw_sockex(env, "FileDescriptor contains invalid fd (< 0)");
        return -1;
    }
    return fd;
}

static int ensure_impl_created(JNIEnv *env, jobject socket) {
    jclass socketCls = (*env)->GetObjectClass(env, socket);
    jmethodID getImplMid = (*env)->GetMethodID(env, socketCls, "getImpl", "()Ljava/net/SocketImpl;");
    if (!getImplMid) {
        (*env)->ExceptionClear(env);
        throw_sockex(env, "Cannot find Socket.getImpl() method");
        return -1;
    }
    jobject impl = (*env)->CallObjectMethod(env, socket, getImplMid);
    if ((*env)->ExceptionCheck(env)) {
        return -1;  /* let the Java exception propagate */
    }
    return impl ? 0 : -1;
}

JNIEXPORT jboolean JNICALL
Java_io_valkey_Mptcp_isMptcpEnabled0(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (mptcp_enabled_value() > 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_valkey_Mptcp_enable0(JNIEnv *env, jclass cls, jobject jsock) {
    (void)cls;
#ifdef __linux__
    if (!jsock) {
        throw_sockex(env, "socket is null");
        return;
    }

    if (mptcp_enabled_value() <= 0) {
        throw_sockex(env, "MPTCP disabled: /proc/sys/net/mptcp/enabled != 1");
        return;
    }

    if (ensure_impl_created(env, jsock) < 0) return;

    int oldfd = extract_fd_from_socket(env, jsock);
    if (oldfd < 0) return;  /* exception already pending */

    struct sockaddr_storage a;
    socklen_t alen = sizeof(a);
    int domain = AF_INET;  /* default to IPv4 */
    if (!getsockname(oldfd, (struct sockaddr *)&a, &alen)) {
        if (a.ss_family == AF_INET6) domain = AF_INET6;
    }

    int newfd = socket(domain, SOCK_STREAM, IPPROTO_MPTCP);
    if (newfd < 0) {
        char msg[160];
        snprintf(msg, sizeof(msg), "socket(IPPROTO_MPTCP) failed: %s", strerror(errno));
        throw_sockex(env, msg);
        return;
    }

    copy_sockopts_and_flags(oldfd, newfd);

    if (dup2(newfd, oldfd) < 0) {
        char msg[80];
        snprintf(msg, sizeof(msg), "dup2 failed: %s", strerror(errno));
        close(newfd);
        throw_sockex(env, msg);
        return;
    }
    close(newfd);  /* newfd is now a duplicate of oldfd; release it */
#else
    (void)jsock;
    throw_sockex(env, "MPTCP not supported on this platform");
#endif
}

