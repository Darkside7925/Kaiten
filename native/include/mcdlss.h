// mcdlss.h — shared declarations for the mcdlss_native glue library.
#pragma once

// ABI version. MUST match NativeBridge.EXPECTED_ABI_VERSION on the Java side.
// Bump whenever a JNI method signature changes.
#define MCDLSS_ABI_VERSION 2

#define MCDLSS_VERSION_STRING "mcdlss_native 0.2.0 (Phase 1: Streamline init)"
