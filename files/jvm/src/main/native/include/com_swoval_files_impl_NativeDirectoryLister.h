/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class com_swoval_files_impl_NativeDirectoryLister */

#ifndef _Included_com_swoval_files_impl_NativeDirectoryLister
#define _Included_com_swoval_files_impl_NativeDirectoryLister
#ifdef __cplusplus
extern "C" {
#endif
#undef com_swoval_files_impl_NativeDirectoryLister_UNKNOWN
#define com_swoval_files_impl_NativeDirectoryLister_UNKNOWN 8L
#undef com_swoval_files_impl_NativeDirectoryLister_DIRECTORY
#define com_swoval_files_impl_NativeDirectoryLister_DIRECTORY 1L
#undef com_swoval_files_impl_NativeDirectoryLister_FILE
#define com_swoval_files_impl_NativeDirectoryLister_FILE 2L
#undef com_swoval_files_impl_NativeDirectoryLister_LINK
#define com_swoval_files_impl_NativeDirectoryLister_LINK 4L
#undef com_swoval_files_impl_NativeDirectoryLister_EOF
#define com_swoval_files_impl_NativeDirectoryLister_EOF 8L
#undef com_swoval_files_impl_NativeDirectoryLister_ENOENT
#define com_swoval_files_impl_NativeDirectoryLister_ENOENT -1L
#undef com_swoval_files_impl_NativeDirectoryLister_EACCES
#define com_swoval_files_impl_NativeDirectoryLister_EACCES -2L
#undef com_swoval_files_impl_NativeDirectoryLister_ENOTDIR
#define com_swoval_files_impl_NativeDirectoryLister_ENOTDIR -3L
#undef com_swoval_files_impl_NativeDirectoryLister_ESUCCESS
#define com_swoval_files_impl_NativeDirectoryLister_ESUCCESS -4L
#undef com_swoval_files_impl_NativeDirectoryLister_MAX_ATTEMPTS
#define com_swoval_files_impl_NativeDirectoryLister_MAX_ATTEMPTS 100L
/*
 * Class:     com_swoval_files_impl_NativeDirectoryLister
 * Method:    errno
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_swoval_files_impl_NativeDirectoryLister_errno(JNIEnv *, jobject,
                                                                              jlong);

/*
 * Class:     com_swoval_files_impl_NativeDirectoryLister
 * Method:    strerror
 * Signature: (I)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_com_swoval_files_impl_NativeDirectoryLister_strerror(JNIEnv *,
                                                                                    jobject, jint);

/*
 * Class:     com_swoval_files_impl_NativeDirectoryLister
 * Method:    openDir
 * Signature: (Ljava/lang/String;)J
 */
JNIEXPORT jlong JNICALL Java_com_swoval_files_impl_NativeDirectoryLister_openDir(JNIEnv *, jobject,
                                                                                 jstring);

/*
 * Class:     com_swoval_files_impl_NativeDirectoryLister
 * Method:    closeDir
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_com_swoval_files_impl_NativeDirectoryLister_closeDir(JNIEnv *, jobject,
                                                                                 jlong);

/*
 * Class:     com_swoval_files_impl_NativeDirectoryLister
 * Method:    nextFile
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_com_swoval_files_impl_NativeDirectoryLister_nextFile(JNIEnv *, jobject,
                                                                                  jlong);

/*
 * Class:     com_swoval_files_impl_NativeDirectoryLister
 * Method:    getType
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_swoval_files_impl_NativeDirectoryLister_getType(JNIEnv *, jobject,
                                                                                jlong);

/*
 * Class:     com_swoval_files_impl_NativeDirectoryLister
 * Method:    getName
 * Signature: (J)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_com_swoval_files_impl_NativeDirectoryLister_getName(JNIEnv *,
                                                                                   jobject, jlong);

#ifdef __cplusplus
}
#endif
#endif
