package com.repository.glasses.listener.mouse

/**
 * Standard USB HID mouse report descriptor with 16-bit X/Y for smooth fast movements.
 *
 * Report format (6 bytes, no report ID):
 *   [0] buttons  -- 3 bits (left=0x01, right=0x02, middle=0x04) + 5 padding
 *   [1] X low    -- 16-bit signed LE relative X
 *   [2] X high
 *   [3] Y low    -- 16-bit signed LE relative Y
 *   [4] Y high
 *   [5] scroll   -- 8-bit signed relative scroll wheel
 */
object HidReportDescriptor {

    const val REPORT_SIZE = 6

    val MOUSE_DESCRIPTOR = byteArrayOf(
        0x05, 0x01,                   // USAGE_PAGE (Generic Desktop)
        0x09, 0x02,                   // USAGE (Mouse)
        0xA1.toByte(), 0x01,          // COLLECTION (Application)
        0x09, 0x01,                   //   USAGE (Pointer)
        0xA1.toByte(), 0x00,          //   COLLECTION (Physical)

        // -- Buttons: 3 bits --
        0x05, 0x09,                   //     USAGE_PAGE (Button)
        0x19, 0x01,                   //     USAGE_MINIMUM (Button 1 - left)
        0x29, 0x03,                   //     USAGE_MAXIMUM (Button 3 - middle)
        0x15, 0x00,                   //     LOGICAL_MINIMUM (0)
        0x25, 0x01,                   //     LOGICAL_MAXIMUM (1)
        0x95.toByte(), 0x03,          //     REPORT_COUNT (3)
        0x75, 0x01,                   //     REPORT_SIZE (1)
        0x81.toByte(), 0x02,          //     INPUT (Data,Var,Abs)

        // -- Padding: 5 bits --
        0x95.toByte(), 0x01,          //     REPORT_COUNT (1)
        0x75, 0x05,                   //     REPORT_SIZE (5)
        0x81.toByte(), 0x01,          //     INPUT (Cnst,Arr,Abs)

        // -- X, Y: 16-bit signed relative --
        0x05, 0x01,                   //     USAGE_PAGE (Generic Desktop)
        0x09, 0x30,                   //     USAGE (X)
        0x09, 0x31,                   //     USAGE (Y)
        0x16.toByte(), 0x01, 0x80.toByte(),  // LOGICAL_MINIMUM (-32767) 16-bit LE
        0x26, 0xFF.toByte(), 0x7F,           // LOGICAL_MAXIMUM (32767) 16-bit LE
        0x75, 0x10,                   //     REPORT_SIZE (16)
        0x95.toByte(), 0x02,          //     REPORT_COUNT (2)
        0x81.toByte(), 0x06,          //     INPUT (Data,Var,Rel)

        // -- Scroll wheel: 8-bit signed relative --
        0x09, 0x38,                   //     USAGE (Wheel)
        0x15, 0x81.toByte(),          //     LOGICAL_MINIMUM (-127)
        0x25, 0x7F,                   //     LOGICAL_MAXIMUM (127)
        0x75, 0x08,                   //     REPORT_SIZE (8)
        0x95.toByte(), 0x01,          //     REPORT_COUNT (1)
        0x81.toByte(), 0x06,          //     INPUT (Data,Var,Rel)

        0xC0.toByte(),                //   END_COLLECTION (Physical)
        0xC0.toByte()                 // END_COLLECTION (Application)
    )
}
