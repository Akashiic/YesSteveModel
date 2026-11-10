/* 3D Futura Engine: the machine-dependent JNI types for Windows x64, as a Windows JDK defines them, so that the DLL
 * can be cross-built on Linux with MinGW-w64. */
#ifndef FUTURA3D_JNI_MD_H
#define FUTURA3D_JNI_MD_H

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall

typedef long jint;
typedef __int64 jlong;
typedef signed char jbyte;

#endif
