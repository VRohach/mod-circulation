package org.folio.circulation.domain.policy;

import static api.support.matchers.FailureMatcher.hasValidationFailure;
import static org.junit.Assert.assertThat;

import org.folio.circulation.domain.Loan;
import org.folio.circulation.domain.RequestQueue;
import org.folio.circulation.support.Result;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import api.support.builders.LoanBuilder;
import api.support.builders.LoanPolicyBuilder;

import java.util.Collections;

public class UnknownLoanPolicyProfileTests {
  @Test
  public void shouldFailCheckOutCalculationForNonRollingProfile() {
    LoanPolicy loanPolicy = LoanPolicy.from(new LoanPolicyBuilder()
      .withName("Invalid Loan Policy")
      .withLoansProfile("Unknown profile")
      .create());

    DateTime loanDate = new DateTime(2018, 3, 14, 11, 14, 54, DateTimeZone.UTC);

    Loan loan = new LoanBuilder()
      .open()
      .withLoanDate(loanDate)
      .asDomainObject();

    final Result<DateTime> result = loanPolicy.calculateInitialDueDate(loan);

    assertThat(result, hasValidationFailure(
      "profile \"Unknown profile\" in the loan policy is not recognised"));
  }

  @Test
  public void shouldFailRenewalCalculationForNonRollingProfile() {
    LoanPolicy loanPolicy = LoanPolicy.from(new LoanPolicyBuilder()
      .withName("Invalid Loan Policy")
      .withLoansProfile("Unknown profile")
      .create());

    DateTime loanDate = new DateTime(2018, 3, 14, 11, 14, 54, DateTimeZone.UTC);

    Loan loan = new LoanBuilder()
      .open()
      .withLoanDate(loanDate)
      .asDomainObject();

    final Result<Loan> result = loanPolicy.renew(loan, DateTime.now(), new RequestQueue(Collections.emptyList()));

    assertThat(result, hasValidationFailure(
      "profile \"Unknown profile\" in the loan policy is not recognised"));
  }
}
