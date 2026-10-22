#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fault-record signal-frame walk, projection and actual JNI payload, without an Android device.

The arm64-only live handler (fault_record_arm64.cpp) needs a real promoted-exception frame and so
cannot run here; what it does with one is call read_fault_record_from_frame(), which these tests
drive with frame bytes laid out the way arch/arm64/kernel/signal.c writes them, then pin the
encoder output byte-for-byte against the Kotlin fixtures so production wire data is verified
against the production parser.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[6]
NATIVE = ROOT / "feature/faultresidue/data/src/main/cpp"
COMMON = ROOT / "core/native/src/main/cpp"
FIXTURES = ROOT / "feature/faultresidue/data/src/test/resources"
JNI = r"""
#include <string>
#define JNIEXPORT
#define JNICALL
using jstring = const char *;
using jobject = void *;
struct JNIEnv {
    std::string storage;
    jstring NewStringUTF(const char *value) { storage = value; return storage.c_str(); }
};
"""
HARNESS = r"""
#include "faultresidue/fault_record_probe.h"
#include "common/payload_codec.h"
#include "jni.h"
#include <array>
#include <cassert>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>

using namespace duckdetector::faultresidue;

namespace {

    void put_le32(unsigned char *bytes, uint32_t value) { std::memcpy(bytes, &value, sizeof(value)); }
    void put_le64(unsigned char *bytes, uint64_t value) { std::memcpy(bytes, &value, sizeof(value)); }

    // A reserved region the way setup_sigframe writes it: fpsimd_context first (0x01468001,
    // 256 bytes), then esr_context (ESR_MAGIC 0x45535201, 16 bytes) only when the kernel stored
    // a code, then the END_MAGIC terminator. The buffer matches the kernel's __reserved area.
    std::array<unsigned char, 4096> make_frame(uint64_t esr) {
        std::array<unsigned char, 4096> frame{};
        put_le32(frame.data() + 0, 0x01468001u);
        put_le32(frame.data() + 4, 256);
        if (esr != 0) {
            put_le32(frame.data() + 256, 0x45535201u);
            put_le32(frame.data() + 260, 16);
            put_le64(frame.data() + 264, esr);
        }
        // The zero header at the next slot is the terminator the kernel writes last.
        return frame;
    }

    void assert_walk() {
        uint64_t value = 0;
        // The ESR record after the fpsimd record is found with its value.
        auto frame = make_frame(0x82000005ULL);
        assert(find_esr_context(frame.data(), frame.size(), &value));
        assert(value == 0x82000005ULL);
        // A frame without the record ends at the terminator and reports nothing.
        auto empty = make_frame(0);
        assert(!find_esr_context(empty.data(), empty.size(), &value));
        // A region starting with the terminator finds nothing.
        std::array<unsigned char, 64> terminator{};
        assert(!find_esr_context(terminator.data(), terminator.size(), &value));
        // A record claiming a size past the buffer cannot make the reader run out of bounds.
        std::array<unsigned char, 64> oversized{};
        put_le32(oversized.data() + 0, 0x45535201u);
        put_le32(oversized.data() + 4, 1000);
        assert(!find_esr_context(oversized.data(), oversized.size(), &value));
        // A buffer too short to even hold a header finds nothing without reading past it.
        assert(!find_esr_context(oversized.data(), 4, &value));
        // An ESR header smaller than esr_context carries no value the walk may load.
        std::array<unsigned char, 64> tiny{};
        put_le32(tiny.data() + 0, 0x45535201u);
        put_le32(tiny.data() + 4, 8);
        assert(!find_esr_context(tiny.data(), tiny.size(), &value));
        // Unknown records are stepped over until the ESR record or the terminator.
        auto chained = make_frame(0x92000006ULL);
        assert(find_esr_context(chained.data(), chained.size(), &value));
        assert(value == 0x92000006ULL);
    }

    std::string encode_read(uint64_t far, uint64_t esr) {
        auto frame = make_frame(esr);
        return encode_fault_record_payload(read_fault_record_from_frame(far, frame.data(), frame.size()));
    }

}  // namespace

extern "C" jstring Java_com_eltavine_duckdetector_features_faultresidue_data_native_FaultResidueNativeBridge_nativeCollectSnapshot(JNIEnv *, jobject);

int main(int argc, char **argv) {
    assert_walk();
    assert(argc == 2);
    const std::string mode = argv[1];
    std::string payload;
    if (mode == "read-zero") {
        payload = encode_read(0, 0);
    } else if (mode == "read-iabt") {
        payload = encode_read(0, 0x82000005ULL);
    } else if (mode == "read-mapped-far") {
        payload = encode_read(0xB400007DULL, 0x8200000FULL);
    } else if (mode == "read-uncanonical") {
        payload = encode_read(0xFFFFFFFFFA13E300ULL, 0x82000004ULL);
    } else if (mode == "unsupported") {
        JNIEnv env;
        // The actual JNI symbol through the actual non-arm64 stub link: binding pinned too.
        Java_com_eltavine_duckdetector_features_faultresidue_data_native_FaultResidueNativeBridge_nativeCollectSnapshot(&env, nullptr);
        payload = env.storage;
    } else if (mode == "setup-failed") {
        FaultRecordResult result;
        result.outcome = FaultRecordOutcome::kSetupFailed;
        result.note = "sigaction failed (errno=1)";
        payload = encode_fault_record_payload(result);
    } else if (mode == "escaped-note") {
        FaultRecordResult result;
        result.outcome = FaultRecordOutcome::kHandlerSilent;
        result.note = "tgkill failed (errno=22)\tand a \\ backslash\nwith newline";
        payload = encode_fault_record_payload(result);
        // A raw LF must not survive inside the value: the escaped form is the two characters
        // backslash and n, so a real newline immediately before "with" cannot appear.
        assert(payload.find("\nwith newline") == std::string::npos);
    } else {
        assert(!"unknown mode");
    }
    std::cout << payload;
    return 0;
}
"""


class FaultRecordHostNativeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = os.environ.get("CXX") or shutil.which("clang++") or shutil.which("g++")
        if not compiler:
            raise RuntimeError("Host C++20 compiler required")
        cls.directory = tempfile.TemporaryDirectory(prefix="duck-fault-record-native-")
        cls.addClassCleanup(cls.directory.cleanup)
        path = Path(cls.directory.name)
        (path / "test.cpp").write_text(HARNESS)
        (path / "jni.h").write_text(JNI)
        cls.binary = path / "test"
        subprocess.run([compiler, "-std=c++20", "-Wall", "-Wextra", "-Werror", "-I", str(path),
                        "-I", str(NATIVE), "-I", str(COMMON), str(path / "test.cpp"),
                        str(NATIVE / "faultresidue/fault_record_probe.cpp"),
                        str(NATIVE / "faultresidue/fault_record_probe_stub.cpp"),
                        str(NATIVE / "faultresidue/native_bridge.cpp"),
                        str(COMMON / "common/payload_codec.cpp"), "-o", str(cls.binary)],
                       check=True, timeout=120)

    def payload(self, mode):
        return subprocess.run([str(self.binary), mode], check=True,
                              capture_output=True, text=True, timeout=10).stdout

    def test_zero_pair_reads_without_esr_record(self):
        raw = self.payload("read-zero")
        self.assertIn("AVAILABLE=1\n", raw)
        self.assertIn("OUTCOME=READ\n", raw)
        self.assertIn("FAR=0\n", raw)
        self.assertIn("ESR=0\n", raw)
        self.assertIn("HAS_ESR=0\n", raw)
        self.assertNotIn("NOTE=", raw)

    def test_instruction_abort_encodes_the_recorded_pair(self):
        raw = self.payload("read-iabt")
        self.assertIn("ESR=2181038085\n", raw)  # 0x82000005
        self.assertIn("HAS_ESR=1\n", raw)

    def test_high_unsigned_far_travels_as_unsigned_decimal(self):
        raw = self.payload("read-uncanonical")
        self.assertIn("FAR=18446744073610191616\n", raw)  # 0xFFFFFFFFFA13E300

    def test_stub_link_reports_unsupported_through_the_real_jni_symbol(self):
        raw = self.payload("unsupported")
        self.assertIn("AVAILABLE=0\n", raw)
        self.assertIn("OUTCOME=UNSUPPORTED_ARCH\n", raw)

    def test_setup_failure_carries_its_note(self):
        raw = self.payload("setup-failed")
        self.assertIn("OUTCOME=SETUP_FAILED\n", raw)
        self.assertIn("NOTE=sigaction failed (errno=1)\n", raw)

    def test_note_escaping_keeps_the_line_format(self):
        raw = self.payload("escaped-note")
        self.assertIn("NOTE=tgkill failed (errno=22)\\tand a \\\\ backslash\\nwith newline\n", raw)

    def test_production_payloads_match_the_kotlin_fixtures(self):
        for mode, fixture in (("read-zero", "fault-record-read-zero.txt"),
                              ("read-iabt", "fault-record-read-iabt.txt"),
                              ("read-mapped-far", "fault-record-read-mapped-far.txt"),
                              ("unsupported", "fault-record-unsupported.txt"),
                              ("setup-failed", "fault-record-setup-failed.txt")):
            with self.subTest(fixture=fixture):
                self.assertEqual((FIXTURES / fixture).read_text(), self.payload(mode))


if __name__ == "__main__":
    unittest.main()
