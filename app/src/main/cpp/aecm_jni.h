//
// Created by User on 11.06.2019.
//

#ifndef AECM_AEC_H
#define AECM_AEC_H

#include <jni.h>
    JNIEXPORT jlong Java_com_repository_glasses_listener_audio_WebRtcAecm_nativeCreateAecmInstance(JNIEnv *env, jclass thiz);
    JNIEXPORT jint Java_com_repository_glasses_listener_audio_WebRtcAecm_nativeFreeAecmInstance(JNIEnv *env, jclass thiz, jlong aecmHandler);
    JNIEXPORT jint Java_com_repository_glasses_listener_audio_WebRtcAecm_nativeInitializeAecmInstance(JNIEnv *env, jclass thiz, jlong aecmHandler, jint sampFreq);
    JNIEXPORT jint Java_com_repository_glasses_listener_audio_WebRtcAecm_nativeBufferFarend(JNIEnv *env, jclass thiz, jlong aecmHandler, jshortArray farend, jint nrOfSamples);
    JNIEXPORT jshortArray Java_com_repository_glasses_listener_audio_WebRtcAecm_nativeAecmProcess(JNIEnv *env, jclass thiz, jlong aecmHandler, const jshortArray nearendNoisy, const jshortArray nearendClean, jshort nrOfSamples, jshort msInSndCardBuf);
    JNIEXPORT jint Java_com_repository_glasses_listener_audio_WebRtcAecm_nativeSetConfig(JNIEnv *env, jclass thiz, jlong aecmHandler, jobject aecmConfig);
#endif //AECM_AEC_H