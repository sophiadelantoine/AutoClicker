/*
 * Copyright (C) 2025 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#include "jni.hpp"
#include "../detector/detection_result.hpp"
#include "../detector/matching/text/text_matching_result.hpp"
#include <limits>

jdoubleArray toJniResult(JNIEnv *env, DetectionResult* result) {
    if (result == nullptr) return nullptr;

    jdouble detectedNumber = std::numeric_limits<double>::lowest();
    auto* textResult = dynamic_cast<TextMatchingResult*>(result);
    if (textResult != nullptr) {
        detectedNumber = textResult->getRecognizedNumber();
    }

    jdoubleArray out = env->NewDoubleArray(7);
    jdouble buffer[7] = {
            result->isDetected() ? 1.0 : 0.0,
            (double) result->getResultAreaCenterX(),
            (double) result->getResultAreaCenterY(),
            (double) result->getResultAreaWidth(),
            (double) result->getResultAreaHeight(),
            result->getResultConfidence(),
            detectedNumber
    };

    env->SetDoubleArrayRegion(out, 0, 7, buffer);
    return out;
}

jobject toJniTextResult(JNIEnv *env, DetectionResult* result) {
    if (result == nullptr) return nullptr;

    // 1) Legacy 7-element numeric array (identical layout to toJniResult).
    jdouble detectedNumber = std::numeric_limits<double>::lowest();
    std::string recognizedText;
    auto* textResult = dynamic_cast<TextMatchingResult*>(result);
    if (textResult != nullptr) {
        detectedNumber = textResult->getRecognizedNumber();
        recognizedText = textResult->getRecognizedText();
    }

    jdoubleArray numericArray = env->NewDoubleArray(7);
    jdouble buffer[7] = {
            result->isDetected() ? 1.0 : 0.0,
            (double) result->getResultAreaCenterX(),
            (double) result->getResultAreaCenterY(),
            (double) result->getResultAreaWidth(),
            (double) result->getResultAreaHeight(),
            result->getResultConfidence(),
            detectedNumber
    };
    env->SetDoubleArrayRegion(numericArray, 0, 7, buffer);

    // 2) Recognized text as raw UTF-8 bytes (preserve multibyte CJK/Arabic; decoded as UTF-8 in Kotlin).
    auto textLength = (jsize) recognizedText.size();
    jbyteArray textBytes = env->NewByteArray(textLength);
    if (textLength > 0) {
        env->SetByteArrayRegion(textBytes, 0, textLength,
                                reinterpret_cast<const jbyte*>(recognizedText.data()));
    }

    // 3) Bundle [numericArray, textBytes] into an Object[2].
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray bundle = env->NewObjectArray(2, objectClass, nullptr);
    env->SetObjectArrayElement(bundle, 0, numericArray);
    env->SetObjectArrayElement(bundle, 1, textBytes);

    env->DeleteLocalRef(numericArray);
    env->DeleteLocalRef(textBytes);
    env->DeleteLocalRef(objectClass);

    return bundle;
}
