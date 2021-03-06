/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.builder.packaging;

/**
 * An exception thrown when trying to add files to a sealed APK.
 */
public final class SealedPackageException extends PackagerException {
    private static final long serialVersionUID = 1L;

    public SealedPackageException(String format, Object... args) {
        super(format, args);
    }

    public SealedPackageException(Throwable cause, String format, Object... args) {
        super(cause, format, args);
    }

    public SealedPackageException(Throwable cause) {
        super(cause);
    }
}