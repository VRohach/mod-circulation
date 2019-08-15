package org.folio.circulation.resources;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.folio.circulation.domain.Entry;
import org.folio.circulation.domain.Loan;
import org.folio.circulation.domain.LoanRepository;
import org.folio.circulation.domain.LoanRepresentation;
import org.folio.circulation.domain.MultipleRecords;
import org.folio.circulation.support.Clients;
import org.folio.circulation.support.CollectionResourceClient;
import org.folio.circulation.support.CqlQuery;
import org.folio.circulation.support.OkJsonResponseResult;
import org.folio.circulation.support.Result;
import org.folio.circulation.support.RouteRegistration;
import org.folio.circulation.support.http.client.Response;
import org.folio.circulation.support.http.server.WebContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.folio.circulation.support.CqlQuery.exactMatch;
import static org.folio.circulation.support.ValidationErrorFailure.failedValidation;

public class ScheduledAnonymizationResource extends Resource {

  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private PeriodicForTenantsResource periodicResource = PeriodicForTenantsResource.getInstance();

  private RoutingContext routingContext;
  private WebContext webContext;

  // Set HashSet here
  private Set<String> tenants = new HashSet<>();

  public ScheduledAnonymizationResource(HttpClient client) {
    super(client);
  }

  @Override
  public void register(Router router) {
    RouteRegistration routeRegistration = new RouteRegistration(
      "/circulation/scheduled-anonymize-processing", router);

    routeRegistration.create(this::initPeriodicForTenant);
  }

  private void initPeriodicForTenant(RoutingContext context) {
      log.info("Okapi touching me.......");

      routingContext = context;

      webContext = new WebContext(routingContext);

      final Clients clients = Clients.create(webContext, client);

      final CollectionResourceClient configsStorage = clients.configurationStorageClient();

      checkInterval(configsStorage);

      startPeriodic();
  }

  private void checkInterval(CollectionResourceClient configsStorage) {
    final Result<CqlQuery> moduleQuery = exactMatch("module", "LOAN_HISTORY");
    final Result<CqlQuery> configNameQuery = CqlQuery.exactMatch("configName","loan_history");

    CompletableFuture<Result<Entry>> resultCompletableFuture = moduleQuery
      .combine(configNameQuery, CqlQuery::and)
      .after(query -> configsStorage.getMany(query, 100))
      .thenApply(result -> result.next(this::mapResponseToEntries)
        .map(MultipleRecords::getRecords)
        .map(configs -> configs.stream().findFirst())
        .next(config -> config.map(Result::succeeded).orElseGet(() ->
          failedValidation("Could not find configuration with matching barcode",
            "configName", "loan_history"))));

    //TODO:get this entry from /entries and check if periodicity changed betwenn storage and current periodic
    Entry entryRepresentation = new Entry();
    resultCompletableFuture.thenApply(entryResult -> entryResult.map(entryRepresentation::extendedEntry))
      .thenApply(OkJsonResponseResult::from)
      .thenAccept(result -> result.writeTo(routingContext.response()));

    log.info("Value: {}", entryRepresentation.asJson());
  }

  private void startPeriodic() {

    if(!periodicResource.tenantExists(webContext.getTenantId())){
      log.info("New tenantId:{} added!", webContext.getTenantId());
      Vertx vertx = routingContext.vertx();
      long periodicId = vertx.setPeriodic(60 * 60, e -> vertx
        .executeBlocking(b -> {
          log.info("Periodic started...");
        },
        res-> log.info("Finished")
      ));
      periodicResource.addPeriodicIdForTenant(webContext.getTenantId(), periodicId);
    }
  }

  private Result<MultipleRecords<Entry>> mapResponseToEntries(Response response) {
    return MultipleRecords.from(response, Entry::from, "configs");
  }


  private void getLoanById(Clients context){
    LoanRepository loanRepository = new LoanRepository(context);

    final LoanRepresentation loanRepresentation = new LoanRepresentation();
    CompletableFuture<Result<Loan>> result = loanRepository.getById("f1d8ed70-ca78-4400-9d8e-938db4aae07a");
    result.thenApply(loanResult -> loanResult.map(loanRepresentation::extendedLoan))
      .thenApply(OkJsonResponseResult::from)
      .thenAccept(ex -> log.info("Loan representaion: {}", loanRepresentation));
  }
}
