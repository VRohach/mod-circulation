package api.support.builders;

public class Period {
  public final Integer duration;
  public final String interval;

  public Period(Integer duration, String interval) {
    this.duration = duration;
    this.interval = interval;
  }

  public static Period weeks(Integer duration) {
    return new Period(duration, "Weeks");
  }

  public static Period days(Integer duration) {
    return new Period(duration, "Days");
  }
}
