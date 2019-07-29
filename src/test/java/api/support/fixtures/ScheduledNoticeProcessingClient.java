package api.support.fixtures;

import static api.support.APITestContext.circulationModuleUrl;
import static api.support.RestAssuredClient.manuallyStartTimedTask;

import java.net.URL;

import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;

public class ScheduledNoticeProcessingClient {

  public void runNoticesProcessing(DateTime mockSystemTime) {
    DateTimeUtils.setCurrentMillisFixed(mockSystemTime.getMillis());
    runNoticesProcessing();
    DateTimeUtils.setCurrentMillisSystem();
  }

  public void runNoticesProcessing() {
    URL url = circulationModuleUrl("/circulation/due-date-scheduled-notices-processing");
    manuallyStartTimedTask(url, 204, "due-date-scheduled-notices-processing-request");
  }

  public void runRequestNoticesProcessing(DateTime mockSystemTime) {
    DateTimeUtils.setCurrentMillisFixed(mockSystemTime.getMillis());
    runRequestNoticesProcessing();
    DateTimeUtils.setCurrentMillisSystem();
  }

  public void runRequestNoticesProcessing() {
    URL url = circulationModuleUrl("/circulation/request-scheduled-notices-processing");
    manuallyStartTimedTask(url, 204, "request-scheduled-notices-processing-request");
  }
}
