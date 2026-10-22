duckdetector_native_unit(duckdetector_faultresidue
    fault_record_probe.cpp
    native_bridge.cpp
)

# The live read exists only where the kernel projects a fault record into the signal frame.
# Other ABIs link the explicit unsupported stub; the split is at link time, not a runtime guess.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_sources(duckdetector_faultresidue PRIVATE fault_record_arm64.cpp)
else()
    target_sources(duckdetector_faultresidue PRIVATE fault_record_probe_stub.cpp)
endif()
