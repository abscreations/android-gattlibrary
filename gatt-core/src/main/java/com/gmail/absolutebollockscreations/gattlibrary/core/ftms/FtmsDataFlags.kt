package com.gmail.absolutebollockscreations.gattlibrary.core.ftms

/**
 * FTMS data-characteristic flag bitmasks (Flags field of each machine data characteristic).
 *
 * Bit positions are from FTMS v1.0 sections 4.5 to 4.9 (Treadmill 0x2ACD, Cross Trainer 0x2ACE,
 * Step Climber 0x2ACF, Stair Climber 0x2AD0, Rower 0x2AD1, Indoor Bike 0x2AD2). Moved from
 * central's internal `Flags` (LIB-19a) so peripheral and simulator encoders can name the same bits.
 */
public object FtmsDataFlags {

    // -------------------------------------------------------------
    // Treadmill (0x2ACD)
    // -------------------------------------------------------------
    public const val TREAD_MOREDATA: Int       = 0x0001
    public const val TREAD_AVGSPEED: Int       = 0x0002
    public const val TREAD_TOTALDISTANCE: Int  = 0x0004
    public const val TREAD_INCLRAMPANGLE: Int  = 0x0008
    public const val TREAD_ELEVATIONGAIN: Int  = 0x0010
    public const val TREAD_INSTANTPACE: Int    = 0x0020
    public const val TREAD_AVGPACE: Int        = 0x0040
    public const val TREAD_EXPENERGY: Int      = 0x0080
    public const val TREAD_HEARTRATE: Int      = 0x0100
    public const val TREAD_METABOLIC: Int      = 0x0200
    public const val TREAD_ELAPSEDTIME: Int    = 0x0400
    public const val TREAD_REMAININGTIME: Int  = 0x0800
    public const val TREAD_BELTFORCEPOWER: Int = 0x1000

    // -------------------------------------------------------------
    // Elliptical (0x2ACE)
    // -------------------------------------------------------------
    public const val ELL_MORE: Int        = 0x0001
    public const val ELL_AVGSPEED: Int    = 0x0002
    public const val ELL_TOTALDIST: Int   = 0x0004
    public const val ELL_STEPCOUNT: Int   = 0x0008
    public const val ELL_STRIDECOUNT: Int = 0x0010
    public const val ELL_ELEVGAIN: Int    = 0x0020
    public const val ELL_INCLINATION: Int = 0x0040
    public const val ELL_RESISTANCE: Int  = 0x0080
    public const val ELL_INSTPOWER: Int   = 0x0100
    public const val ELL_AVGPOWER: Int    = 0x0200
    public const val ELL_EXPENERGY: Int   = 0x0400
    public const val ELL_HEARTRATE: Int   = 0x0800
    public const val ELL_METABOLIC: Int   = 0x1000
    public const val ELL_ELAPSED: Int     = 0x2000
    public const val ELL_REMAINING: Int   = 0x4000

    // -------------------------------------------------------------
    // Rower (0x2AD1)
    // -------------------------------------------------------------
    public const val ROW_MORE: Int        = 0x0001
    public const val ROW_AVGSTROKE: Int   = 0x0002
    public const val ROW_TOTALDIST: Int   = 0x0004
    public const val ROW_INSTPACE: Int    = 0x0008
    public const val ROW_AVGPACE: Int     = 0x0010
    public const val ROW_INSTPOWER: Int   = 0x0020
    public const val ROW_AVGPOWER: Int    = 0x0040
    public const val ROW_RESISTANCE: Int  = 0x0080
    public const val ROW_EXPENERGY: Int   = 0x0100
    public const val ROW_HEARTRATE: Int   = 0x0200
    public const val ROW_METABOLIC: Int   = 0x0400
    public const val ROW_ELAPSED: Int     = 0x0800
    public const val ROW_REMAINING: Int   = 0x1000

    // -------------------------------------------------------------
    // Indoor Bike (0x2AD2)
    // -------------------------------------------------------------
    // Spec bit order (FTMS Indoor Bike Data, 0x2AD2):
    //   bit0 More Data, bit1 Average Speed, bit2 Instantaneous Cadence, bit3 Average Cadence, ...
    // NOTE: bits 1 and 2 were previously swapped here. Both the encoder
    // (BtServiceBikeData) and the parser (doBike) emit/consume the fields in the
    // correct spec order (average speed BEFORE cadence), so correcting the two
    // constants fixes both directions with no other change required.
    public const val BIKE_MORE: Int       = 0x0001
    public const val BIKE_AVGSPEED: Int   = 0x0002
    public const val BIKE_CADENCE: Int    = 0x0004
    public const val BIKE_AVGCAD: Int     = 0x0008
    public const val BIKE_TOTALDIST: Int  = 0x0010
    public const val BIKE_RESISTANCE: Int = 0x0020
    public const val BIKE_INSTPOWER: Int  = 0x0040
    public const val BIKE_AVGPOWER: Int   = 0x0080
    public const val BIKE_EXPENERGY: Int  = 0x0100
    public const val BIKE_HEARTRATE: Int  = 0x0200
    public const val BIKE_METABOLIC: Int  = 0x0400
    public const val BIKE_ELAPSED: Int    = 0x0800
    public const val BIKE_REMAINING: Int  = 0x1000

    // -------------------------------------------------------------
    // Stepper (0x2ACF)
    // -------------------------------------------------------------
    public const val STEP_MORE: Int       = 0x0001
    public const val STEP_STEPRATE: Int   = 0x0002
    public const val STEP_AVGSTEPR: Int   = 0x0004
    public const val STEP_ELEVGAIN: Int   = 0x0008
    public const val STEP_EXPENERGY: Int  = 0x0010
    public const val STEP_HEARTRATE: Int  = 0x0020
    public const val STEP_METABOLIC: Int  = 0x0040
    public const val STEP_ELAPSED: Int    = 0x0080
    public const val STEP_REMAINING: Int  = 0x0100

    // -------------------------------------------------------------
    // Climber (0x2AD0)
    // -------------------------------------------------------------
    public const val CLIMB_MORE: Int      = 0x0001
    public const val CLIMB_STEPRATE: Int  = 0x0002
    public const val CLIMB_AVGSTEPR: Int  = 0x0004
    public const val CLIMB_ELEVGAIN: Int  = 0x0008
    public const val CLIMB_STRIDE: Int    = 0x0010
    public const val CLIMB_EXPENERGY: Int = 0x0020
    public const val CLIMB_HEARTRATE: Int = 0x0040
    public const val CLIMB_METABOLIC: Int = 0x0080
    public const val CLIMB_ELAPSED: Int   = 0x0100
    public const val CLIMB_REMAINING: Int = 0x0200
}
