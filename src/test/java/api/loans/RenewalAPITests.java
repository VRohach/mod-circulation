package api.loans;

import static api.support.builders.FixedDueDateSchedule.forDay;
import static api.support.builders.FixedDueDateSchedule.wholeMonth;
import static api.support.builders.ItemBuilder.CHECKED_OUT;
import static api.support.fixtures.CalendarExamples.CASE_FRI_SAT_MON_SERVICE_POINT_ID;
import static api.support.fixtures.CalendarExamples.CASE_FRI_SAT_MON_SERVICE_POINT_NEXT_DAY;
import static api.support.fixtures.CalendarExamples.CASE_FRI_SAT_MON_SERVICE_POINT_PREV_DAY;
import static api.support.fixtures.CalendarExamples.CASE_WED_THU_FRI_SERVICE_POINT_ID;
import static api.support.fixtures.CalendarExamples.END_TIME_SECOND_PERIOD;
import static api.support.fixtures.CalendarExamples.START_TIME_FIRST_PERIOD;
import static api.support.fixtures.CalendarExamples.START_TIME_SECOND_PERIOD;
import static api.support.fixtures.CalendarExamples.WEDNESDAY_DATE;
import static api.support.matchers.ItemStatusCodeMatcher.hasItemStatus;
import static api.support.matchers.TextDateTimeMatcher.isEquivalentTo;
import static api.support.matchers.TextDateTimeMatcher.withinSecondsAfter;
import static api.support.matchers.UUIDMatcher.is;
import static api.support.matchers.ValidationErrorMatchers.hasErrorWith;
import static api.support.matchers.ValidationErrorMatchers.hasMessage;
import static api.support.matchers.ValidationErrorMatchers.hasParameter;
import static api.support.matchers.ValidationErrorMatchers.hasUUIDParameter;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.folio.circulation.domain.policy.library.ClosedLibraryStrategyUtils.END_OF_A_DAY;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.net.MalformedURLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.folio.circulation.domain.policy.DueDateManagement;
import org.folio.circulation.domain.policy.Period;
import org.folio.circulation.support.http.client.IndividualResource;
import org.folio.circulation.support.http.client.Response;
import org.folio.circulation.support.http.client.ResponseHandler;
import org.folio.circulation.support.http.server.ValidationError;
import org.hamcrest.Matcher;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.joda.time.DateTimeUtils;
import org.joda.time.DateTimeZone;
import org.joda.time.Seconds;
import org.junit.Test;

import api.support.APITestContext;
import api.support.APITests;
import api.support.builders.CheckOutByBarcodeRequestBuilder;
import api.support.builders.FixedDueDateSchedule;
import api.support.builders.FixedDueDateSchedulesBuilder;
import api.support.builders.LoanPolicyBuilder;
import api.support.fixtures.ConfigurationExample;
import io.vertx.core.json.JsonObject;

abstract class RenewalAPITests extends APITests {
  abstract Response attemptRenewal(IndividualResource user, IndividualResource item);

  abstract IndividualResource renew(IndividualResource user, IndividualResource item);

  abstract Matcher<ValidationError> hasUserRelatedParameter(IndividualResource user);

  abstract Matcher<ValidationError> hasItemRelatedParameter(IndividualResource item);

  abstract Matcher<ValidationError> hasItemNotFoundMessage(IndividualResource item);

  @Test
  public void canRenewRollingLoanFromSystemDate()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource jessica = usersFixture.jessica();

    final UUID loanId = loansFixture.checkOutByBarcode(smallAngryPlanet, jessica,
      new DateTime(2018, 4, 21, 11, 21, 43, DateTimeZone.UTC))
      .getId();

    //TODO: Renewal based upon system date,
    // needs to be approximated, at least until we introduce a calendar and clock
    DateTime approximateRenewalDate = DateTime.now(DateTimeZone.UTC);

    final JsonObject renewedLoan = renew(smallAngryPlanet, jessica).getJson();

    assertThat(renewedLoan.getString("id"), is(loanId.toString()));

    assertThat("user ID should match barcode",
      renewedLoan.getString("userId"), is(jessica.getId().toString()));

    assertThat("item ID should match barcode",
      renewedLoan.getString("itemId"), is(smallAngryPlanet.getId().toString()));

    assertThat("status should be open",
      renewedLoan.getJsonObject("status").getString("name"), is("Open"));

    assertThat("action should be renewed",
      renewedLoan.getString("action"), is("renewed"));

    assertThat("renewal count should be incremented",
      renewedLoan.getInteger("renewalCount"), is(1));

    assertThat("last loan policy should be stored",
      renewedLoan.getString("loanPolicyId"),
      is(loanPoliciesFixture.canCirculateRolling().getId()));

    assertThat("due date should be approximately 3 weeks after renewal date, based upon loan policy",
      renewedLoan.getString("dueDate"),
      withinSecondsAfter(Seconds.seconds(10), approximateRenewalDate.plusWeeks(3)));

    smallAngryPlanet = itemsClient.get(smallAngryPlanet);

    assertThat(smallAngryPlanet, hasItemStatus(CHECKED_OUT));
  }

  @Test
  public void canRenewRollingLoanFromCurrentDueDate()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    configClient.create(ConfigurationExample.utcTimezoneConfiguration());

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource jessica = usersFixture.jessica();

    final IndividualResource loan = loansFixture.checkOutByBarcode(smallAngryPlanet, jessica,
      new DateTime(2018, 4, 21, 11, 21, 43, DateTimeZone.UTC));

    final UUID loanId = loan.getId();

    LoanPolicyBuilder currentDueDateRollingPolicy = new LoanPolicyBuilder()
      .withName("Current Due Date Rolling Policy")
      .rolling(Period.months(2))
      .renewFromCurrentDueDate();

    UUID dueDateLimitedPolicyId = loanPoliciesFixture
      .create(currentDueDateRollingPolicy).getId();

    useLoanPolicyAsFallback(
      dueDateLimitedPolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    final JsonObject renewedLoan = renew(smallAngryPlanet, jessica).getJson();

    assertThat(renewedLoan.getString("id"), is(loanId.toString()));

    assertThat("user ID should match barcode",
      renewedLoan.getString("userId"), is(jessica.getId().toString()));

    assertThat("item ID should match barcode",
      renewedLoan.getString("itemId"), is(smallAngryPlanet.getId().toString()));

    assertThat("status should be open",
      renewedLoan.getJsonObject("status").getString("name"), is("Open"));

    assertThat("action should be renewed",
      renewedLoan.getString("action"), is("renewed"));

    assertThat("renewal count should be incremented",
      renewedLoan.getInteger("renewalCount"), is(1));

    assertThat("last loan policy should be stored",
      renewedLoan.getString("loanPolicyId"), is(dueDateLimitedPolicyId.toString()));

    assertThat("due date should be 2 months after initial due date date",
      renewedLoan.getString("dueDate"),
      isEquivalentTo(new DateTime(2018, 7, 12, 11, 21, 43, DateTimeZone.UTC)));

    smallAngryPlanet = itemsClient.get(smallAngryPlanet);

    assertThat(smallAngryPlanet, hasItemStatus(CHECKED_OUT));
  }

  @Test
  public void canRenewUsingDueDateLimitedRollingLoanPolicy()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    FixedDueDateSchedulesBuilder dueDateLimitSchedule = new FixedDueDateSchedulesBuilder()
      .withName("March Only Due Date Limit")
      .addSchedule(wholeMonth(2018, 3));

    final UUID dueDateLimitScheduleId = loanPoliciesFixture.createSchedule(
      dueDateLimitSchedule).getId();

    LoanPolicyBuilder dueDateLimitedPolicy = new LoanPolicyBuilder()
      .withName("Due Date Limited Rolling Policy")
      .rolling(Period.weeks(2))
      .limitedBySchedule(dueDateLimitScheduleId)
      .renewFromCurrentDueDate();

    UUID dueDateLimitedPolicyId = loanPoliciesFixture
      .create(dueDateLimitedPolicy).getId();

    useLoanPolicyAsFallback(
      dueDateLimitedPolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource steve = usersFixture.steve();

    final DateTime loanDate = new DateTime(2018, 3, 7, 11, 43, 54, DateTimeZone.UTC);

    loansFixture.checkOutByBarcode(
      new CheckOutByBarcodeRequestBuilder()
        .forItem(smallAngryPlanet)
        .to(steve)
        .on(loanDate)
        .at(UUID.randomUUID()));

    final IndividualResource response = renew(smallAngryPlanet, steve);

    final JsonObject loan = response.getJson();

    assertThat("last loan policy should be stored",
      loan.getString("loanPolicyId"), is(dueDateLimitedPolicyId.toString()));

    assertThat("due date should be limited by schedule",
      loan.getString("dueDate"),
      isEquivalentTo(new DateTime(2018, 3, 31, 23, 59, 59, DateTimeZone.UTC)));
  }

  @Test
  public void canRenewRollingLoanUsingDifferentPeriod()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource jessica = usersFixture.jessica();

    final IndividualResource loan = loansFixture.checkOutByBarcode(smallAngryPlanet, jessica,
      new DateTime(2018, 4, 21, 11, 21, 43, DateTimeZone.UTC));

    final UUID loanId = loan.getId();

    LoanPolicyBuilder currentDueDateRollingPolicy = new LoanPolicyBuilder()
      .withName("Current Due Date Different Period Rolling Policy")
      .rolling(Period.months(2))
      .renewFromCurrentDueDate()
      .renewWith(Period.months(1));

    UUID dueDateLimitedPolicyId = loanPoliciesFixture
      .create(currentDueDateRollingPolicy).getId();

    useLoanPolicyAsFallback(
      dueDateLimitedPolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    final JsonObject renewedLoan = renew(smallAngryPlanet, jessica).getJson();

    assertThat(renewedLoan.getString("id"), is(loanId.toString()));

    assertThat("user ID should match barcode",
      renewedLoan.getString("userId"), is(jessica.getId().toString()));

    assertThat("item ID should match barcode",
      renewedLoan.getString("itemId"), is(smallAngryPlanet.getId().toString()));

    assertThat("status should be open",
      renewedLoan.getJsonObject("status").getString("name"), is("Open"));

    assertThat("action should be renewed",
      renewedLoan.getString("action"), is("renewed"));

    assertThat("renewal count should be incremented",
      renewedLoan.getInteger("renewalCount"), is(1));

    assertThat("last loan policy should be stored",
      renewedLoan.getString("loanPolicyId"), is(dueDateLimitedPolicyId.toString()));

    assertThat("due date should be 2 months after initial due date date",
      renewedLoan.getString("dueDate"),
      isEquivalentTo(new DateTime(2018, 6, 12, 11, 21, 43, DateTimeZone.UTC)));

    smallAngryPlanet = itemsClient.get(smallAngryPlanet);

    assertThat(smallAngryPlanet, hasItemStatus(CHECKED_OUT));
  }

  @Test
  public void canRenewUsingAlternateDueDateLimitedRollingLoanPolicy()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    FixedDueDateSchedulesBuilder dueDateLimitSchedule = new FixedDueDateSchedulesBuilder()
      .withName("March Only Due Date Limit")
      .addSchedule(wholeMonth(2018, 3));

    final UUID dueDateLimitScheduleId = loanPoliciesFixture.createSchedule(
      dueDateLimitSchedule).getId();

    LoanPolicyBuilder dueDateLimitedPolicy = new LoanPolicyBuilder()
      .withName("Due Date Limited Rolling Policy")
      .rolling(Period.weeks(3))
      .renewFromCurrentDueDate()
      .renewWith(Period.days(8), dueDateLimitScheduleId);

    UUID dueDateLimitedPolicyId = loanPoliciesFixture
      .create(dueDateLimitedPolicy).getId();

    useLoanPolicyAsFallback(
      dueDateLimitedPolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource steve = usersFixture.steve();

    final DateTime loanDate = new DateTime(2018, 3, 4, 11, 43, 54, DateTimeZone.UTC);

    loansFixture.checkOutByBarcode(
      new CheckOutByBarcodeRequestBuilder()
        .forItem(smallAngryPlanet)
        .to(steve)
        .on(loanDate)
        .at(UUID.randomUUID()));

    final IndividualResource response = renew(smallAngryPlanet, steve);

    final JsonObject loan = response.getJson();

    assertThat("last loan policy should be stored",
      loan.getString("loanPolicyId"), is(dueDateLimitedPolicyId.toString()));

    assertThat("due date should be limited by schedule",
      loan.getString("dueDate"),
      isEquivalentTo(new DateTime(2018, 3, 31, 23, 59, 59, DateTimeZone.UTC)));
  }

  @Test
  public void canRenewUsingLoanDueDateLimitSchedulesWhenDifferentPeriodAndNotAlternateLimits()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    FixedDueDateSchedulesBuilder dueDateLimitSchedule = new FixedDueDateSchedulesBuilder()
      .withName("March Only Due Date Limit")
      .addSchedule(wholeMonth(2018, 3));

    final UUID dueDateLimitScheduleId = loanPoliciesFixture.createSchedule(
      dueDateLimitSchedule).getId();

    LoanPolicyBuilder dueDateLimitedPolicy = new LoanPolicyBuilder()
      .withName("Due Date Limited Rolling Policy")
      .rolling(Period.weeks(3))
      .limitedBySchedule(dueDateLimitScheduleId)
      .renewFromCurrentDueDate()
      .renewWith(Period.days(8));

    UUID dueDateLimitedPolicyId = loanPoliciesFixture
      .create(dueDateLimitedPolicy).getId();

    useLoanPolicyAsFallback(
      dueDateLimitedPolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource steve = usersFixture.steve();

    final DateTime loanDate = new DateTime(2018, 3, 4, 11, 43, 54, DateTimeZone.UTC);

    loansFixture.checkOutByBarcode(
      new CheckOutByBarcodeRequestBuilder()
        .forItem(smallAngryPlanet)
        .to(steve)
        .on(loanDate)
        .at(UUID.randomUUID()));

    final IndividualResource response = renew(smallAngryPlanet, steve);

    final JsonObject loan = response.getJson();

    assertThat("last loan policy should be stored",
      loan.getString("loanPolicyId"), is(dueDateLimitedPolicyId.toString()));

    assertThat("due date should be limited by schedule",
      loan.getString("dueDate"),
      isEquivalentTo(new DateTime(2018, 3, 31, 23, 59, 59, DateTimeZone.UTC)));
  }

  @Test
  public void canCheckOutUsingFixedDueDateLoanPolicy()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    //TODO: Need to be able to inject system date here
    final DateTime renewalDate = DateTime.now(DateTimeZone.UTC);
    //e.g. Clock.freeze(renewalDate)

    FixedDueDateSchedulesBuilder fixedDueDateSchedules = new FixedDueDateSchedulesBuilder()
      .withName("Kludgy Fixed Due Date Schedule")
      .addSchedule(wholeMonth(2018, 2))
      .addSchedule(forDay(renewalDate));

    final UUID fixedDueDateSchedulesId = loanPoliciesFixture.createSchedule(
      fixedDueDateSchedules).getId();

    LoanPolicyBuilder dueDateLimitedPolicy = new LoanPolicyBuilder()
      .withName("Fixed Due Date Policy")
      .fixed(fixedDueDateSchedulesId)
      .renewFromSystemDate();

    UUID fixedDueDatePolicyId = loanPoliciesFixture.create(dueDateLimitedPolicy)
      .getId();

    useLoanPolicyAsFallback(
      fixedDueDatePolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource steve = usersFixture.steve();

    final DateTime loanDate = new DateTime(2018, 2, 10, 11, 23, 12, DateTimeZone.UTC);

    loansFixture.checkOutByBarcode(
      new CheckOutByBarcodeRequestBuilder()
        .forItem(smallAngryPlanet)
        .to(steve)
        .on(loanDate)
        .at(UUID.randomUUID()));

    final IndividualResource response = renew(smallAngryPlanet, steve);

    final JsonObject loan = response.getJson();

    assertThat("last loan policy should be stored",
      loan.getString("loanPolicyId"), is(fixedDueDatePolicyId.toString()));

    assertThat("renewal count should be incremented",
      loan.getInteger("renewalCount"), is(1));

    final DateTime endOfRenewalDate = renewalDate
      .withTimeAtStartOfDay()
      .withHourOfDay(23)
      .withMinuteOfHour(59)
      .withSecondOfMinute(59);

    assertThat("due date should be defined by schedule",
      loan.getString("dueDate"), isEquivalentTo(endOfRenewalDate));
  }

  @Test
  public void canRenewMultipleTimesUpToRenewalLimit()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource jessica = usersFixture.jessica();

    LoanPolicyBuilder limitedRenewalsPolicy = new LoanPolicyBuilder()
      .withName("Limited Renewals Policy")
      .rolling(Period.days(2))
      .renewFromCurrentDueDate()
      .limitedRenewals(3);

    UUID limitedRenewalsPolicyId = loanPoliciesFixture
      .create(limitedRenewalsPolicy).getId();

    useLoanPolicyAsFallback(
      limitedRenewalsPolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    final IndividualResource loan = loansFixture.checkOutByBarcode(smallAngryPlanet, jessica,
      new DateTime(2018, 4, 21, 11, 21, 43, DateTimeZone.UTC));

    final UUID loanId = loan.getId();

    renew(smallAngryPlanet, jessica).getJson();

    renew(smallAngryPlanet, jessica);

    final JsonObject renewedLoan = renew(smallAngryPlanet, jessica).getJson();

    assertThat(renewedLoan.getString("id"), is(loanId.toString()));

    assertThat("status should be open",
      renewedLoan.getJsonObject("status").getString("name"), is("Open"));

    assertThat("action should be renewed",
      renewedLoan.getString("action"), is("renewed"));

    assertThat("renewal count should be incremented",
      renewedLoan.getInteger("renewalCount"), is(3));

    assertThat("last loan policy should be stored",
      renewedLoan.getString("loanPolicyId"), is(limitedRenewalsPolicyId.toString()));

    assertThat("due date should be 8 days after initial loan date date",
      renewedLoan.getString("dueDate"),
      isEquivalentTo(new DateTime(2018, 4, 29, 11, 21, 43, DateTimeZone.UTC)));

    smallAngryPlanet = itemsClient.get(smallAngryPlanet);

    assertThat(smallAngryPlanet, hasItemStatus(CHECKED_OUT));
  }

  @Test
  public void canGetRenewedLoan()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource jessica = usersFixture.jessica();

    final UUID loanId = loansFixture.checkOutByBarcode(smallAngryPlanet, jessica,
      new DateTime(2018, 4, 21, 11, 21, 43, DateTimeZone.UTC))
      .getId();

    //TODO: Renewal based upon system date,
    // needs to be approximated, at least until we introduce a calendar and clock
    DateTime approximateRenewalDate = DateTime.now(DateTimeZone.UTC);

    final IndividualResource response = renew(smallAngryPlanet, jessica);

    final CompletableFuture<Response> getCompleted = new CompletableFuture<>();

    client.get(APITestContext.circulationModuleUrl(response.getLocation()),
      ResponseHandler.json(getCompleted));

    final Response getResponse = getCompleted.get(2, TimeUnit.SECONDS);

    assertThat(getResponse.getStatusCode(), is(HTTP_OK));

    JsonObject renewedLoan = getResponse.getJson();

    assertThat(renewedLoan.getString("id"), is(loanId.toString()));

    assertThat("user ID should match barcode",
      renewedLoan.getString("userId"), is(jessica.getId().toString()));

    assertThat("item ID should match barcode",
      renewedLoan.getString("itemId"), is(smallAngryPlanet.getId().toString()));

    assertThat("status should be open",
      renewedLoan.getJsonObject("status").getString("name"), is("Open"));

    assertThat("action should be renewed",
      renewedLoan.getString("action"), is("renewed"));

    assertThat("renewal count should be incremented",
      renewedLoan.getInteger("renewalCount"), is(1));

    assertThat("last loan policy should be stored",
      renewedLoan.getString("loanPolicyId"),
      is(loanPoliciesFixture.canCirculateRolling().getId()));

    assertThat("due date should be approximately 3 weeks after renewal date, based upon loan policy",
      renewedLoan.getString("dueDate"),
      withinSecondsAfter(Seconds.seconds(10), approximateRenewalDate.plusWeeks(3)));
  }

  @Test
  public void cannotRenewWhenLoanPolicyDoesNotExist()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource jessica = usersFixture.jessica();

    final UUID unknownLoanPolicyId = UUID.randomUUID();

    loansFixture.checkOutByBarcode(smallAngryPlanet, jessica,
      new DateTime(2018, 4, 21, 11, 21, 43, DateTimeZone.UTC));

    useLoanPolicyAsFallback(
      unknownLoanPolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    final Response response = loansFixture.attemptRenewal(500, smallAngryPlanet, jessica);

    assertThat(response.getBody(), is(String.format(
      "Loan policy %s could not be found, please check circulation rules", unknownLoanPolicyId)));
  }

  @Test
  public void cannotRenewWhenRenewalLimitReached()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource jessica = usersFixture.jessica();

    LoanPolicyBuilder limitedRenewalsPolicy = new LoanPolicyBuilder()
      .withName("Limited Renewals Policy")
      .rolling(Period.days(2))
      .renewFromCurrentDueDate()
      .limitedRenewals(3);

    UUID limitedRenewalsPolicyId = loanPoliciesFixture
      .create(limitedRenewalsPolicy).getId();

    useLoanPolicyAsFallback(
      limitedRenewalsPolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    loansFixture.checkOutByBarcode(smallAngryPlanet, jessica,
      new DateTime(2018, 4, 21, 11, 21, 43, DateTimeZone.UTC));

    renew(smallAngryPlanet, jessica);
    renew(smallAngryPlanet, jessica);
    renew(smallAngryPlanet, jessica);

    final Response response = attemptRenewal(smallAngryPlanet, jessica);

    assertThat(response.getJson(), hasErrorWith(allOf(
      hasMessage("loan at maximum renewal number"),
      hasLoanPolicyIdParameter(limitedRenewalsPolicyId),
      hasLoanPolicyNameParameter("Limited Renewals Policy"))));
  }

  @Test
  public void multipleReasonsWhyCannotRenewWhenRenewalLimitReachedAndDueDateNotChanged()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource jessica = usersFixture.jessica();

    //TODO: Replace with better example when can fix system date
    FixedDueDateSchedulesBuilder yesterdayAndTodayOnlySchedules = new FixedDueDateSchedulesBuilder()
      .withName("Yesterday and Today Only Due Date Limit")
      .addSchedule(FixedDueDateSchedule.yesterdayOnly())
      .addSchedule(FixedDueDateSchedule.todayOnly());

    final UUID yesterdayAndTodayOnlySchedulesId
      = loanPoliciesFixture.createSchedule(yesterdayAndTodayOnlySchedules).getId();

    LoanPolicyBuilder limitedRenewalsPolicy = new LoanPolicyBuilder()
      .withName("Limited Renewals And Limited Due Date Policy")
      .fixed(yesterdayAndTodayOnlySchedulesId)
      .limitedRenewals(1);

    UUID limitedRenewalsPolicyId = loanPoliciesFixture
      .create(limitedRenewalsPolicy).getId();

    useLoanPolicyAsFallback(
      limitedRenewalsPolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    loansFixture.checkOutByBarcode(smallAngryPlanet, jessica,
      DateTime.now(DateTimeZone.UTC).minusDays(1)).getJson();

    renew(smallAngryPlanet, jessica);

    final Response response = attemptRenewal(smallAngryPlanet, jessica);

    assertThat(response.getJson(), hasErrorWith(allOf(
      hasMessage("loan at maximum renewal number"),
      hasLoanPolicyIdParameter(limitedRenewalsPolicyId),
      hasLoanPolicyNameParameter("Limited Renewals And Limited Due Date Policy"))));

    assertThat(response.getJson(), hasErrorWith(allOf(
      hasMessage("renewal would not change the due date"),
      hasLoanPolicyIdParameter(limitedRenewalsPolicyId),
      hasLoanPolicyNameParameter("Limited Renewals And Limited Due Date Policy"))));
  }

  @Test
  public void cannotRenewWhenNonRenewableRollingPolicy()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource jessica = usersFixture.jessica();

    LoanPolicyBuilder limitedRenewalsPolicy = new LoanPolicyBuilder()
      .withName("Non Renewable Policy")
      .rolling(Period.days(2))
      .notRenewable();

    UUID notRenewablePolicyId = loanPoliciesFixture
      .create(limitedRenewalsPolicy).getId();

    useLoanPolicyAsFallback(
      notRenewablePolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    loansFixture.checkOutByBarcode(smallAngryPlanet, jessica,
      new DateTime(2018, 4, 21, 11, 21, 43, DateTimeZone.UTC));

    final Response response = attemptRenewal(smallAngryPlanet, jessica);

    assertThat(response.getJson(), hasErrorWith(allOf(
      hasMessage("loan is not renewable"),
      hasLoanPolicyIdParameter(notRenewablePolicyId),
      hasLoanPolicyNameParameter("Non Renewable Policy"))));
  }

  @Test
  public void cannotRenewWhenRollingLoanPolicyCannotDetermineRenewFrom() throws Exception {
    String expectedName = "Non Renewable";
    String expectedErrorMsgNotRenewable = "loan is not renewable";
    String expectedErrorMsgCannotDetermine = "cannot determine when to renew from";

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource jessica = usersFixture.jessica();

    DateTime loanDueDate =
      new DateTime(2018, DateTimeConstants.APRIL, 21, 11, 21, 43);

    loansFixture.checkOutByBarcode(smallAngryPlanet, jessica, loanDueDate);

    LoanPolicyBuilder nonRenewablePolicy = new LoanPolicyBuilder()
      .withName(expectedName)
      .rolling(Period.days(2))
      .notRenewable();

    UUID notRenewablePolicyId = loanPoliciesFixture
      .create(nonRenewablePolicy).getId();

    useLoanPolicyAsFallback(
      notRenewablePolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId());

    JsonObject jsonResponse = loansFixture.attemptRenewal(422, smallAngryPlanet, jessica).getJson();

    assertThat(jsonResponse, hasErrorWith(allOf(
      hasMessage(expectedErrorMsgNotRenewable),
      hasLoanPolicyIdParameter(notRenewablePolicyId),
      hasLoanPolicyNameParameter(expectedName))));

    assertThat(jsonResponse, hasErrorWith(allOf(
      hasMessage(expectedErrorMsgCannotDetermine),
      hasLoanPolicyIdParameter(notRenewablePolicyId),
      hasLoanPolicyNameParameter(expectedName))));
  }


  @Test
  public void cannotRenewWhenRollingLoanPolicyHasDueDateInPast() throws Exception {
    String expectedName = "Non Renewable";
    String expectedErrorMsgNotRenewable = "loan is not renewable";
    String expectedErrorMsgCannotDetermine = "renewal date falls outside of date ranges in rolling loan policy";

    final IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource jessica = usersFixture.jessica();

    DateTime loanDueDate =
      new DateTime(2018, DateTimeConstants.APRIL, 21, 11, 21, 43);

    loansFixture.checkOutByBarcode(smallAngryPlanet, jessica, loanDueDate);

    FixedDueDateSchedulesBuilder dueDateLimitSchedule = new FixedDueDateSchedulesBuilder()
      .withName("Past March Only Due Date Limit")
      .addSchedule(wholeMonth(2001, 3));

    final UUID dueDateLimitScheduleId = loanPoliciesFixture.createSchedule(
      dueDateLimitSchedule).getId();

    LoanPolicyBuilder dueDateLimitedPolicy = new LoanPolicyBuilder()
      .withName(expectedName)
      .rolling(Period.weeks(2))
      .limitedBySchedule(dueDateLimitScheduleId)
      .notRenewable();

    UUID notRenewablePolicyId = loanPoliciesFixture
      .create(dueDateLimitedPolicy).getId();

    useLoanPolicyAsFallback(
      notRenewablePolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId());

    JsonObject jsonResponse = loansFixture.attemptRenewal(422, smallAngryPlanet, jessica).getJson();

    assertThat(jsonResponse, hasErrorWith(allOf(
      hasMessage(expectedErrorMsgNotRenewable),
      hasLoanPolicyIdParameter(notRenewablePolicyId),
      hasLoanPolicyNameParameter(expectedName))));

    assertThat(jsonResponse, hasErrorWith(allOf(
      hasMessage(expectedErrorMsgCannotDetermine),
      hasLoanPolicyIdParameter(notRenewablePolicyId),
      hasLoanPolicyNameParameter(expectedName))));
  }

  @Test
  public void cannotRenewWhenNonRenewableFixedPolicy()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource jessica = usersFixture.jessica();

    //TODO: Replace with better example when can fix system date
    FixedDueDateSchedulesBuilder todayOnlySchedules = new FixedDueDateSchedulesBuilder()
      .withName("Today Only Due Date Limit")
      .addSchedule(FixedDueDateSchedule.todayOnly());

    final UUID todayOnlySchedulesId = loanPoliciesFixture.createSchedule(
      todayOnlySchedules).getId();

    LoanPolicyBuilder limitedRenewalsPolicy = new LoanPolicyBuilder()
      .withName("Non Renewable Policy")
      .fixed(todayOnlySchedulesId)
      .notRenewable();

    UUID notRenewablePolicyId = loanPoliciesFixture
      .create(limitedRenewalsPolicy).getId();

    useLoanPolicyAsFallback(
      notRenewablePolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    loansFixture.checkOutByBarcode(smallAngryPlanet, jessica,
      DateTime.now(DateTimeZone.UTC));

    final Response response = attemptRenewal(smallAngryPlanet, jessica);

    assertThat(response.getJson(), hasErrorWith(allOf(
      hasMessage("loan is not renewable"),
      hasLoanPolicyIdParameter(notRenewablePolicyId),
      hasLoanPolicyNameParameter("Non Renewable Policy"))));
  }

  @Test
  public void cannotRenewWhenItemIsNotLoanable()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource jessica = usersFixture.jessica();

    LoanPolicyBuilder policyForCheckout = new LoanPolicyBuilder()
      .withName("Policy for checkout")
      .rolling(Period.days(2))
      .notRenewable();

    UUID notRenewablePolicyId = loanPoliciesFixture
      .create(policyForCheckout).getId();

    useLoanPolicyAsFallback(
      notRenewablePolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    loansFixture.checkOutByBarcode(smallAngryPlanet, jessica,
      new DateTime(2018, 4, 21, 11, 21, 43, DateTimeZone.UTC));

    LoanPolicyBuilder nonLoanablePolicy = new LoanPolicyBuilder()
      .withName("Non loanable policy")
      .withLoanable(false);

    UUID notLoanablePolicyId = loanPoliciesFixture
      .create(nonLoanablePolicy).getId();

    useLoanPolicyAsFallback(
      notLoanablePolicyId,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId()
    );

    final Response response = attemptRenewal(smallAngryPlanet, jessica);

    assertThat(response.getJson(), hasErrorWith(allOf(
      hasMessage("item is not loanable"),
      hasLoanPolicyIdParameter(notLoanablePolicyId),
      hasLoanPolicyNameParameter("Non loanable policy"))));
  }

  @Test
  public void cannotRenewWhenLoaneeCannotBeFound()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    final IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource steve = usersFixture.steve();

    loansFixture.checkOutByBarcode(smallAngryPlanet, steve);

    usersFixture.remove(steve);

    Response response = attemptRenewal(smallAngryPlanet, steve);

    //Occurs when current loanee is not found, so relates to loan rather than user in request
    assertThat(response.getJson(), hasErrorWith(allOf(
      hasMessage("user is not found"),
      hasUUIDParameter("userId", steve.getId()))));
  }

  @Test
  public void cannotRenewWhenItemCannotBeFound()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    final IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    final IndividualResource steve = usersFixture.steve();

    loansFixture.checkOutByBarcode(smallAngryPlanet, steve);

    itemsClient.delete(smallAngryPlanet.getId());

    Response response = attemptRenewal(smallAngryPlanet, steve);

    assertThat(response.getJson(), hasErrorWith(allOf(
      hasItemNotFoundMessage(smallAngryPlanet),
      hasItemRelatedParameter(smallAngryPlanet))));
  }

  @Test
  public void cannotRenewLoanForDifferentUser()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    IndividualResource james = usersFixture.james();
    final IndividualResource jessica = usersFixture.jessica();

    loansFixture.checkOutByBarcode(smallAngryPlanet, jessica,
      new DateTime(2018, 4, 21, 11, 21, 43, DateTimeZone.UTC));

    final Response response = attemptRenewal(smallAngryPlanet, james);

    assertThat(response.getJson(), hasErrorWith(allOf(
      hasMessage("Cannot renew item checked out to different user"),
      hasUserRelatedParameter(james))));
  }

  @Test
  public void testMoveToEndOfPreviousOpenDay()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {
    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    IndividualResource jessica = usersFixture.jessica();
    UUID checkoutServicePointId = UUID.fromString(CASE_FRI_SAT_MON_SERVICE_POINT_ID);
    int loanPeriodDays = 5;
    int renewPeriodDays = 3;

    DateTime loanDate =
      new DateTime(2019, DateTimeConstants.JANUARY, 25, 10, 0, DateTimeZone.UTC);

    LoanPolicyBuilder loanPolicy = new LoanPolicyBuilder()
      .withName("Loan policy")
      .rolling(Period.days(loanPeriodDays))
      .renewWith(Period.days(renewPeriodDays))
      .withClosedLibraryDueDateManagement(
        DueDateManagement.KEEP_THE_CURRENT_DUE_DATE.getValue());

    UUID loanPolicyIdForCheckOut = loanPolicyClient.create(loanPolicy).getId();
    useLoanPolicyAsFallback(loanPolicyIdForCheckOut,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId());

    loansFixture.checkOutByBarcode(
      new CheckOutByBarcodeRequestBuilder()
        .forItem(smallAngryPlanet)
        .to(jessica)
        .at(checkoutServicePointId)
        .on(loanDate));

    UUID loanPolicyIdForRenew = loanPolicyClient.create(loanPolicy
      .withName("For renew")
      .withClosedLibraryDueDateManagement(
        DueDateManagement.MOVE_TO_THE_END_OF_THE_PREVIOUS_OPEN_DAY.getValue())
    ).getId();
    useLoanPolicyAsFallback(loanPolicyIdForRenew,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId());

    JsonObject renewedLoan = renew(smallAngryPlanet, jessica).getJson();

    DateTime expectedDate =
      CASE_FRI_SAT_MON_SERVICE_POINT_PREV_DAY
        .toDateTime(END_OF_A_DAY, DateTimeZone.UTC);
    assertThat("due date should be " + expectedDate,
      renewedLoan.getString("dueDate"), isEquivalentTo(expectedDate));
  }

  @Test
  public void testMoveToEndOfNextOpenDay()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {
    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    IndividualResource jessica = usersFixture.jessica();
    UUID checkoutServicePointId = UUID.fromString(CASE_FRI_SAT_MON_SERVICE_POINT_ID);
    int loanPeriodDays = 5;
    int renewPeriodDays = 3;

    DateTime loanDate =
      new DateTime(2019, DateTimeConstants.JANUARY, 25, 10, 0, DateTimeZone.UTC);

    LoanPolicyBuilder loanPolicy = new LoanPolicyBuilder()
      .withName("Loan policy")
      .rolling(Period.days(loanPeriodDays))
      .renewWith(Period.days(renewPeriodDays))
      .withClosedLibraryDueDateManagement(
        DueDateManagement.KEEP_THE_CURRENT_DUE_DATE.getValue());

    UUID loanPolicyIdForCheckOut = loanPolicyClient.create(loanPolicy).getId();
    useLoanPolicyAsFallback(loanPolicyIdForCheckOut,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId());

    loansFixture.checkOutByBarcode(
      new CheckOutByBarcodeRequestBuilder()
        .forItem(smallAngryPlanet)
        .to(jessica)
        .at(checkoutServicePointId)
        .on(loanDate));

    UUID loanPolicyIdForRenew = loanPolicyClient.create(loanPolicy
      .withName("For renew")
      .withClosedLibraryDueDateManagement(
        DueDateManagement.MOVE_TO_THE_END_OF_THE_NEXT_OPEN_DAY.getValue())
    ).getId();
    useLoanPolicyAsFallback(loanPolicyIdForRenew,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId());

    JsonObject renewedLoan = renew(smallAngryPlanet, jessica).getJson();

    DateTime expectedDate =
      CASE_FRI_SAT_MON_SERVICE_POINT_NEXT_DAY
        .toDateTime(END_OF_A_DAY, DateTimeZone.UTC);
    assertThat("due date should be " + expectedDate,
      renewedLoan.getString("dueDate"), isEquivalentTo(expectedDate));
  }

  @Test
  public void testMoveToEndOfNextOpenServicePointHours()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {
    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    IndividualResource jessica = usersFixture.jessica();
    UUID checkoutServicePointId = UUID.fromString(CASE_FRI_SAT_MON_SERVICE_POINT_ID);
    int loanPeriodHours = 8;

    DateTime loanDate =
      new DateTime(2019, DateTimeConstants.FEBRUARY, 1, 10, 0, DateTimeZone.UTC);

    LoanPolicyBuilder loanPolicy = new LoanPolicyBuilder()
      .withName("Loan policy")
      .rolling(Period.hours(loanPeriodHours))
      .withClosedLibraryDueDateManagement(
        DueDateManagement.KEEP_THE_CURRENT_DUE_DATE.getValue());

    UUID loanPolicyIdForCheckOut = loanPolicyClient.create(loanPolicy).getId();
    useLoanPolicyAsFallback(loanPolicyIdForCheckOut,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId());

    loansFixture.checkOutByBarcode(
      new CheckOutByBarcodeRequestBuilder()
        .forItem(smallAngryPlanet)
        .to(jessica)
        .at(checkoutServicePointId)
        .on(loanDate));

    UUID loanPolicyIdForRenew = loanPolicyClient.create(loanPolicy
      .withName("For renew")
      .withClosedLibraryDueDateManagement(
        DueDateManagement.MOVE_TO_BEGINNING_OF_NEXT_OPEN_SERVICE_POINT_HOURS.getValue())
    ).getId();
    useLoanPolicyAsFallback(loanPolicyIdForRenew,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId());

    JsonObject renewedLoan = renew(smallAngryPlanet, jessica).getJson();

    DateTime expectedDate =
      CASE_FRI_SAT_MON_SERVICE_POINT_NEXT_DAY
        .toDateTime(START_TIME_FIRST_PERIOD, DateTimeZone.UTC);
    assertThat("due date should be " + expectedDate,
      renewedLoan.getString("dueDate"), isEquivalentTo(expectedDate));
  }

  @Test
  public void testMoveToEndOfCurrentServicePointHours()
    throws InterruptedException,
    MalformedURLException,
    TimeoutException,
    ExecutionException {
    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    IndividualResource jessica = usersFixture.jessica();
    UUID checkoutServicePointId = UUID.fromString(CASE_WED_THU_FRI_SERVICE_POINT_ID);
    int loanPeriodHours = 12;
    int renewPeriodHours = 5;

    DateTime loanDate =
      WEDNESDAY_DATE.toDateTime(START_TIME_SECOND_PERIOD, DateTimeZone.UTC);

    LoanPolicyBuilder loanPolicy = new LoanPolicyBuilder()
      .withName("Loan policy")
      .rolling(Period.hours(loanPeriodHours))
      .renewWith(Period.hours(renewPeriodHours))
      .withClosedLibraryDueDateManagement(
        DueDateManagement.KEEP_THE_CURRENT_DUE_DATE_TIME.getValue());

    UUID loanPolicyIdForCheckOut = loanPolicyClient.create(loanPolicy).getId();
    useLoanPolicyAsFallback(loanPolicyIdForCheckOut,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId());

    loansFixture.checkOutByBarcode(
      new CheckOutByBarcodeRequestBuilder()
        .forItem(smallAngryPlanet)
        .to(jessica)
        .at(checkoutServicePointId)
        .on(loanDate));

    UUID loanPolicyIdForRenew = loanPolicyClient.create(loanPolicy
      .withName("For renew")
      .withClosedLibraryDueDateManagement(
        DueDateManagement.MOVE_TO_END_OF_CURRENT_SERVICE_POINT_HOURS.getValue())
    ).getId();
    useLoanPolicyAsFallback(loanPolicyIdForRenew,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId());

    DateTimeUtils.setCurrentMillisFixed(loanDate.plusHours(1).getMillis());
    JsonObject renewedLoan = renew(smallAngryPlanet, jessica).getJson();
    DateTimeUtils.setCurrentMillisSystem();

    DateTime expectedDate =
      WEDNESDAY_DATE
        .toDateTime(END_TIME_SECOND_PERIOD, DateTimeZone.UTC);
    assertThat("due date should be " + expectedDate,
      renewedLoan.getString("dueDate"), isEquivalentTo(expectedDate));
  }

  @Test
  public void testRespectSelectedTimezoneForDueDateCalculations() throws Exception {

    String expectedTimeZone = "America/New_York";

    Response response = configClient.create(ConfigurationExample.newYorkTimezoneConfiguration())
      .getResponse();
    assertThat(response.getBody(), containsString(expectedTimeZone));

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    IndividualResource jessica = usersFixture.jessica();
    UUID checkoutServicePointId = UUID.fromString(CASE_FRI_SAT_MON_SERVICE_POINT_ID);
    int loanPeriodHours = 8;

    DateTime loanDate =
      new DateTime(2019, DateTimeConstants.FEBRUARY, 1, 10, 0, DateTimeZone.forID(expectedTimeZone));

    LoanPolicyBuilder loanPolicy = new LoanPolicyBuilder()
      .withName("Loan policy")
      .rolling(Period.hours(loanPeriodHours))
      .withClosedLibraryDueDateManagement(
        DueDateManagement.KEEP_THE_CURRENT_DUE_DATE.getValue());

    UUID loanPolicyIdForCheckOut = loanPolicyClient.create(loanPolicy).getId();
    useLoanPolicyAsFallback(loanPolicyIdForCheckOut,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId());

    loansFixture.checkOutByBarcode(
      new CheckOutByBarcodeRequestBuilder()
        .forItem(smallAngryPlanet)
        .to(jessica)
        .at(checkoutServicePointId)
        .on(loanDate));

    UUID loanPolicyIdForRenew = loanPolicyClient.create(loanPolicy
      .withName("For renew")
      .withClosedLibraryDueDateManagement(
        DueDateManagement.MOVE_TO_BEGINNING_OF_NEXT_OPEN_SERVICE_POINT_HOURS.getValue())
    ).getId();
    useLoanPolicyAsFallback(loanPolicyIdForRenew,
      requestPoliciesFixture.allowAllRequestPolicy().getId(),
      noticePoliciesFixture.activeNotice().getId());

    JsonObject renewedLoan = renew(smallAngryPlanet, jessica).getJson();

    DateTime expectedDate =
      CASE_FRI_SAT_MON_SERVICE_POINT_NEXT_DAY
        .toDateTime(START_TIME_FIRST_PERIOD, DateTimeZone.forID(expectedTimeZone));

    assertThat(response.getBody(), containsString(expectedTimeZone));

    assertThat("due date should be " + expectedDate,
      renewedLoan.getString("dueDate"), isEquivalentTo(expectedDate));
  }

  private Matcher<ValidationError> hasLoanPolicyIdParameter(UUID loanPolicyId) {
    return hasUUIDParameter("loanPolicyId", loanPolicyId);
  }

  private Matcher<ValidationError> hasLoanPolicyNameParameter(String policyName) {
    return hasParameter("loanPolicyName", policyName);
  }
}
