package in.anubhav.rest;

import in.anubhav.service.GpsPublisher;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/gps")
public class GpsController {

    private final GpsPublisher gpsPublisher;

    public GpsController(GpsPublisher gpsPublisher) {
        this.gpsPublisher = gpsPublisher;
    }

    @PostMapping("/publish")
    public String publishGps(@RequestBody String gpsPayload) {
        gpsPublisher.publishGps(gpsPayload);
        return "GPS data published: " + gpsPayload;
    }

    @GetMapping("/publish-bulk")
    public String publishBulk(@RequestParam(defaultValue = "100000") int count) {
        gpsPublisher.sendBulkMessages("gps.telemetry.queue", count);// ✅ queue
        return "✅ Published " + count + " messages";
    }
}
