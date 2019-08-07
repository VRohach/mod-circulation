package org.folio.circulation.resources;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.folio.circulation.domain.Loan;
import org.folio.circulation.domain.LoanRepository;
import org.folio.circulation.domain.LoanRepresentation;
import org.folio.circulation.support.Clients;
import org.folio.circulation.support.OkJsonResponseResult;
import org.folio.circulation.support.Result;
import org.folio.circulation.support.RouteRegistration;
import org.folio.circulation.support.http.server.WebContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class ScheduledAnonymizationResource extends Resource {
  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  // Set HashSet here
  private Set<String> tenants = new HashSet<>();

  public ScheduledAnonymizationResource(HttpClient client) {
    super(client);
  }

  @Override
  public void register(Router router) {
    RouteRegistration routeRegistration = new RouteRegistration(
      "/circulation/scheduled-anonymize-processing", router);

    routeRegistration.create(this::initClientsContext);
  }

  private void initClientsContext(RoutingContext routingContext) {

      log.info("Okapi touching me.......");


      final WebContext context = new WebContext(routingContext);
      final Clients clients = Clients.create(context, client);


      if(!tenants.contains(context.getTenantId())){
        log.info("New tenantId:{} added!",context.getTenantId());
        tenants.add(context.getTenantId());
        Vertx vertx = routingContext.vertx();
        vertx.setPeriodic(60 * 60, e -> vertx.executeBlocking(b -> {
            log.info("Periodic started...");
            getLoanById(clients);
          },
          res-> log.info("Finished")
        ));
      }

      //TODO: Logic for timeout configuration changing via Handler for periodic
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
