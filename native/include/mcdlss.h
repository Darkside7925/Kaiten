// mcdlss.h â€” shared declarations for the mcdlss_native glue library.
#pragma once

// ABI version. MUST match NativeBridge.EXPECTED_ABI_VERSION on the Java side.
// Bump whenever a JNI method signature changes.
#define MCDLSS_ABI_VERSION 3

#define MCDLSS_VERSION_STRING "mcdlss_native 0.5.0 (Phase 5: DLSS Frame Generation)"
