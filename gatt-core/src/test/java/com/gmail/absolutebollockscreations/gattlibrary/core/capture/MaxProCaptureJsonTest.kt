package com.gmail.absolutebollockscreations.gattlibrary.core.capture

import org.junit.Assert.*
import org.junit.Test

class MaxProCaptureJsonTest {
    @Test fun `interpretation is numeric NDJSON with explicit missing values`() {
        val event = MaxProInterpretationEvent("c1", "V1_CAPTURE_LAYOUT", 3, 4, 12,
            linkedMapOf("battery_raw" to 100.0, "left_calibrated_travel_m" to null))
        val line = CaptureJson.encode(CaptureRecord(1, 2, event))
        assertEquals("{\"t\":1,\"seq\":2,\"conn\":\"c1\",\"ev\":\"maxpro_interpretation\",\"status\":\"V1_CAPTURE_LAYOUT\",\"frame\":3,\"arrivalMs\":4,\"notificationBytes\":12,\"values\":{\"battery_raw\":100.0,\"left_calibrated_travel_m\":null}}", line)
        assertFalse(line.contains("\n"))
    }
}
