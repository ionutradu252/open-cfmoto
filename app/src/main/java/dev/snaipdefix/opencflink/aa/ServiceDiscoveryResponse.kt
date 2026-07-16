// Adapted from headunit-revived (AGPLv3): aap/protocol/messages/ServiceDiscoveryResponse.kt
// Video-only head-unit profile for OpenCfMoto: advertises 720x1280 PORTRAIT H.264 video
// (composited aspect-correct into the bike's requested canvas — the CFDL26 dash is a tall 800x951
// panel), a driving-status sensor, a touchscreen input service, and a PCM microphone (required for
// AA bring-up). Audio sink, navigation-status, media-playback and bluetooth services from HUR are
// intentionally dropped (video-only v1 — see docs 03 M5).
package dev.snaipdefix.opencflink.aa

import com.google.protobuf.Message
import dev.snaipdefix.opencflink.AaResolution
import dev.snaipdefix.opencflink.AaVideoSpec
import dev.snaipdefix.opencflink.BikeProfileHolder
import dev.snaipdefix.opencflink.aa.proto.Common
import dev.snaipdefix.opencflink.aa.proto.Control
import dev.snaipdefix.opencflink.aa.proto.Media
import dev.snaipdefix.opencflink.aa.proto.Sensors

class ServiceDiscoveryResponse
    : AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, makeProto()) {

    companion object {
        // Video geometry is PROFILE-DRIVEN: the active bike profile (chosen from the QR modelId
        // before AA starts, refined by CLIENT_INFO) supplies the AA resolution/orientation + dpi.
        // CFDL16 → landscape 800x480 @160; CFDL26 (1000 MT-X) → portrait 720x1280 @240. These read
        // the live holder so AaReceiver's decoder fallback dims track the selected profile too.
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

            // --- Sensor service (driving status + night) ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_SEN
                service.sensorSourceService = Control.Service.SensorSourceService.newBuilder().also { s ->
                    s.addSensors(makeSensorType(Sensors.SensorType.DRIVING_STATUS))
                    s.addSensors(makeSensorType(Sensors.SensorType.NIGHT))
                }.build()
            }.build())

            // --- Video service (720x1280 PORTRAIT H.264 baseline, 30 fps) ---
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
                            // 800x480 exactly. Declare the difference as margins: AA then keeps its
                            // UI inside the visible band and leaves the rest empty, and the
                            // compositor's fill/crop removes exactly that empty band — perfect fit,
                            // nothing real cropped. See BikeProfile.panelSize.
                            setMarginWidth(marginW(spec))
                            setMarginHeight(marginH(spec))
                            setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                        }.build()
                    )
                }.build()
            }.build())

            // --- Input service (touchscreen; declared for compatibility, driven by voice in v1) ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_INP
                service.inputSourceService = Control.Service.InputSourceService.newBuilder().also { inp ->
                    // D-pad / rotary keycodes so the phone's on-screen buttons can drive AA. Advertising
                    // these makes Android Auto render a focus-navigable UI (highlighted items), which is
                    // the only way to control it on a non-touch dash.
                    AaInput.SUPPORTED_KEYCODES.forEach { inp.addKeycodesSupported(it) }
                    // Advertise a touchscreen only on dashes that actually have one. On a non-touch dash
                    // (supportScreenTouch=false) advertising touch would put AA in touch mode with no
                    // on-screen focus for the D-pad to move.
                    if (BikeProfileHolder.active.supportsScreenTouch) {
                        inp.touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                            setWidth(spec.width)
                            setHeight(spec.height)
                        }.build()
                    }
                }.build()
            }.build())

            // --- Audio2 sink (system sounds). Android Auto rejects a head unit that advertises
            //     no audio sink and drops the connection right after service discovery, so we
            //     always advertise this even though the PCM is discarded — nav audio plays via the
            //     phone's own output → BT helmet, not through us. See AapMessageHandlerType. ---
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

            // --- Microphone service (required for AA connection / Assistant) ---
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
        private fun marginW(spec: AaVideoSpec): Int =
            BikeProfileHolder.active.panelSize?.let { (spec.width - it.first).coerceAtLeast(0) } ?: 0

        private fun marginH(spec: AaVideoSpec): Int =
            BikeProfileHolder.active.panelSize?.let { (spec.height - it.second).coerceAtLeast(0) } ?: 0

        private fun makeSensorType(type: Sensors.SensorType): Control.Service.SensorSourceService.Sensor =
            Control.Service.SensorSourceService.Sensor.newBuilder().setType(type).build()
    }
}
