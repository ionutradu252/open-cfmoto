// follows headunit-revived's DrivingStatusEvent (AGPLv3)
package dev.snaipdefix.opencflink.aa

import com.google.protobuf.Message
import dev.snaipdefix.opencflink.aa.proto.Sensors

/** tells AA whether it's dark out, so it uses its night theme. see NightModeSender. */
class NightModeEvent(night: Boolean)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, makeProto(night)) {

    companion object {
        private fun makeProto(night: Boolean): Message =
            Sensors.SensorBatch.newBuilder()
                .addNightMode(Sensors.SensorBatch.NightData.newBuilder().setIsNightMode(night))
                .build()
    }
}
