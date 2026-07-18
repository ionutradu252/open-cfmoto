// adapted from headunit-revived (AGPLv3): aap/protocol/messages/ServiceDiscoveryResponse.kt
// what we tell AA we are: h264 video at the active profile's resolution, sensors (driving status +
// night), an input service (keys, and a touchscreen if the dash has one), a system-sound audio sink
// and a pcm mic. the nav-status, media-playback and bluetooth services from HUR are dropped.
package dev.snaipdefix.opencflink.aa

import com.google.protobuf.Message
import dev.snaipdefix.opencflink.AaResolution
import dev.snaipdefix.opencflink.AaVideoSpec
import dev.snaipdefix.opencflink.BikeProfileHolder
import dev.snaipdefix.opencflink.ScreenMargins
import dev.snaipdefix.opencflink.aa.proto.Common
import dev.snaipdefix.opencflink.aa.proto.Control
import dev.snaipdefix.opencflink.aa.proto.Media
import dev.snaipdefix.opencflink.aa.proto.Sensors

class ServiceDiscoveryResponse
    : AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, makeProto()) {

    companion object {
        // geometry comes from the active profile (picked from the qr modelId before AA starts,
        // refined by CLIENT_INFO). read the live holder so AaReceiver's decoder fallback tracks it.
        val AA_WIDTH: Int get() = BikeProfileHolder.active.aaVideo.width
        val AA_HEIGHT: Int get() = BikeProfileHolder.active.aaVideo.height

        private fun protoResolution(r: AaResolution):
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType = when (r) {
            AaResolution.LANDSCAPE_800x480 ->
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
            AaResolution.LANDSCAPE_1280x720 ->
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720
            AaResolution.PORTRAIT_720x1280 ->
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
            AaResolution.PORTRAIT_1080x1920 ->
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920
        }

        private fun makeProto(): Message {
            val spec = BikeProfileHolder.active.aaVideo
            val services = mutableListOf<Control.Service>()

            // sensors: driving status + night
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_SEN
                service.sensorSourceService = Control.Service.SensorSourceService.newBuilder().also { s ->
                    s.addSensors(makeSensorType(Sensors.SensorType.DRIVING_STATUS))
                    s.addSensors(makeSensorType(Sensors.SensorType.NIGHT))
                }.build()
            }.build())

            // video
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_VID
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
                    sink.availableType = Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                    sink.audioType = Media.AudioStreamType.NONE
                    sink.availableWhileInCall = true
                    sink.addVideoConfigs(
                        Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                            codecResolution = protoResolution(spec.resolution)
                            frameRate = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30
                            setDensity(spec.dpi)
                            // AA only renders at fixed resolutions, so an 800x400 panel can't match
                            // 800x480 exactly. declare the difference as margins and AA keeps its ui
                            // in the visible band, leaving the rest empty, the compositor's fill
                            // then crops exactly that band. see BikeProfile.panelSize.
                            setMarginWidth(marginW(spec))
                            setMarginHeight(marginH(spec))
                            setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                        }.build()
                    )
                }.build()
            }.build())

            // input: keys always, touchscreen only if the dash has one
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_INP
                service.inputSourceService = Control.Service.InputSourceService.newBuilder().also { inp ->
                    // d-pad / rotary keycodes so the app's buttons can drive AA. advertising these
                    // makes AA draw a focus highlight, the only way to control a non-touch dash.
                    AaInput.SUPPORTED_KEYCODES.forEach { inp.addKeycodesSupported(it) }
                    // only claim a touchscreen on dashes that have one. on a non-touch dash it would
                    // put AA in touch mode with no focus for the d-pad to move.
                    if (BikeProfileHolder.active.supportsScreenTouch) {
                        // the inset ui size, not the video size. AA scales this declaration onto
                        // video-minus-margins, so it has to match the space AaCompositor.mapCanvasToUi
                        // sends coords in. declaring the full canvas here is why 800NK touch was
                        // off even after the frame parse fix.
                        inp.touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                            setWidth(spec.width - marginW(spec))
                            setHeight(spec.height - marginH(spec))
                        }.build()
                    }
                }.build()
            }.build())

            // audio2 sink (system sounds). AA drops the connection right after service discovery if
            // there's no audio sink at all, so advertise it even though we throw the pcm away, AA's
            // audio comes out of the phone and reaches the helmet over bluetooth anyway.
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_AU2
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
                    sink.availableType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                    sink.audioType = Media.AudioStreamType.SYSTEM
                    sink.addAudioConfigs(
                        Media.AudioConfiguration.newBuilder().apply {
                            sampleRate = 16000
                            numberOfBits = 16
                            numberOfChannels = 1
                        }.build()
                    )
                }.build()
            }.build())

            // mic. required for the connection, and for the assistant.
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_MIC
                service.mediaSourceService = Control.Service.MediaSourceService.newBuilder().also { src ->
                    src.type = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                    src.audioConfig = Media.AudioConfiguration.newBuilder().apply {
                        sampleRate = 16000
                        numberOfBits = 16
                        numberOfChannels = 1
                    }.build()
                }.build()
            }.build())

            return Control.ServiceDiscoveryResponse.newBuilder().apply {
                make = "OpenCfMoto"
                model = "MotoPlay"
                year = "2024"
                vehicleId = "opencfmoto"
                headUnitModel = "CFDL16-6GUV"
                headUnitMake = "CFMoto"
                headUnitSoftwareBuild = "1"
                headUnitSoftwareVersion = "0.1.0"
                driverPosition = Control.DriverPosition.DRIVER_POSITION_LEFT
                canPlayNativeMediaDuringVr = false
                hideProjectedClock = false
                setDisplayName("OpenCfMoto")
                setHeadunitInfo(Common.HeadUnitInfo.newBuilder().apply {
                    setHeadUnitMake("CFMoto")
                    setHeadUnitModel("CFDL16-6GUV")
                    setMake("OpenCfMoto")
                    setModel("MotoPlay")
                    setYear("2024")
                    setVehicleId("opencfmoto")
                    setHeadUnitSoftwareBuild("1")
                    setHeadUnitSoftwareVersion("0.1.0")
                }.build())
                addAllServices(services)
            }.build()
        }

        /** Empty band AA should leave on each axis so its UI stays inside the dash's real panel. */
        /**
         * the area we ask AA to lay its ui out in: the profile's panel, less any screen margins.
         *
         * the compositor already blacks the margins out and drops taps there, but AA doesn't know
         * that, so on its own it would keep putting its top bar under a strip the rider can neither
         * see nor touch. subtracting here shrinks AA's ui to the part that's actually visible.
         *
         * service discovery happens once per connect, so a margin change only reaches AA on the
         * next connect. the compositor half applies immediately.
         *
         * also records what we actually declared, see BikeProfileHolder.declaredPanel.
         */
        private fun effectivePanel(): Pair<Int, Int>? =
            BikeProfileHolder.active.panelSize?.let { (w, h) ->
                (w - ScreenMargins.left - ScreenMargins.right).coerceAtLeast(1) to
                    (h - ScreenMargins.top - ScreenMargins.bottom).coerceAtLeast(1)
            }?.also { BikeProfileHolder.declaredPanel = it }

        private fun marginW(spec: AaVideoSpec): Int =
            effectivePanel()?.let { (spec.width - it.first).coerceAtLeast(0) } ?: 0

        private fun marginH(spec: AaVideoSpec): Int =
            effectivePanel()?.let { (spec.height - it.second).coerceAtLeast(0) } ?: 0

        private fun makeSensorType(type: Sensors.SensorType): Control.Service.SensorSourceService.Sensor =
            Control.Service.SensorSourceService.Sensor.newBuilder().setType(type).build()
    }
}
