package cloud.wordplus.realtime.ui.calls

/**
 * Someone's camera changing its codec in the middle of a room call: the calls' own machine
 * ([cloud.wordplus.realtime.calls.CameraSwitch], which `GroupCall` keeps for every room), under the name Better
 * Messages' screen files use for it. The room hears the frames that end a switch; the tiles and the stage draw the
 * "Optimizing video quality..." placeholder while it is up.
 */
typealias CallsCameraSwitch = cloud.wordplus.realtime.calls.CameraSwitch
