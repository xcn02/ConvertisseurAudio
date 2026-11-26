#include <jni.h>
#include <stdint.h>
#include <math.h>
#include "rnnoise.h"
JNIEXPORT jlong JNICALL Java_fr_local_convertisseuraudio_RnnoiseNative_create(JNIEnv *e, jobject self) {
    return (jlong)(intptr_t)rnnoise_create(NULL);
}
JNIEXPORT void JNICALL Java_fr_local_convertisseuraudio_RnnoiseNative_destroy(JNIEnv *e, jobject self, jlong handle) {
    if (handle) rnnoise_destroy((DenoiseState *)(intptr_t)handle);
}
JNIEXPORT void JNICALL Java_fr_local_convertisseuraudio_RnnoiseNative_process(JNIEnv *e, jobject self, jlong handle, jshortArray samples) {
    if (!handle || (*e)->GetArrayLength(e,samples)!=480) return;
    jshort s[480]; float in[480], out[480];
    (*e)->GetShortArrayRegion(e,samples,0,480,s);
    for(int i=0;i<480;i++) in[i]=(float)s[i];
    rnnoise_process_frame((DenoiseState *)(intptr_t)handle,out,in);
    for(int i=0;i<480;i++) s[i]=(jshort)lrintf(fmaxf(-32768.f,fminf(32767.f,out[i])));
    (*e)->SetShortArrayRegion(e,samples,0,480,s);
}
