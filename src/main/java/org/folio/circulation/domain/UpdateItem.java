package org.folio.circulation.domain;

import io.vertx.core.json.JsonObject;
import org.folio.circulation.support.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.CompletableFuture;

import static org.folio.circulation.domain.ItemStatus.AVAILABLE;
import static org.folio.circulation.domain.ItemStatus.CHECKED_OUT;

public class UpdateItem {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final CollectionResourceClient itemsStorageClient;

  public UpdateItem(Clients clients) {
    itemsStorageClient = clients.itemsStorage();
  }

  public CompletableFuture<HttpResult<LoanAndRelatedRecords>> onCheckOut(
    LoanAndRelatedRecords relatedRecords) {

    try {
      RequestQueue requestQueue = relatedRecords.getRequestQueue();

      //Hack for creating returned loan - should distinguish further up the chain
      if(relatedRecords.getLoan().isClosed()) {
        return skip(relatedRecords);
      }

      final String prospectiveStatus;

      if(requestQueue != null) {
        prospectiveStatus = requestQueue.hasOutstandingRequests()
          ? RequestType.from(requestQueue.getHighestPriorityRequest()).toCheckedOutItemStatus()
          : CHECKED_OUT;
      }
      else {
        prospectiveStatus = CHECKED_OUT;
      }

      if(relatedRecords.getInventoryRecords().isNotSameStatus(prospectiveStatus)) {
        return internalUpdate(relatedRecords.getInventoryRecords(), prospectiveStatus)
          .thenApply(updatedItemResult -> updatedItemResult.map(
            relatedRecords::withItem));
      }
      else {
        return skip(relatedRecords);
      }
    }
    catch (Exception ex) {
      logException(ex);
      return CompletableFuture.completedFuture(
        HttpResult.failure(new ServerErrorFailure(ex)));
    }
  }

  public CompletableFuture<HttpResult<LoanAndRelatedRecords>> onLoanUpdate(
    LoanAndRelatedRecords loanAndRelatedRecords) {

    try {
      final InventoryRecords inventoryRecords = loanAndRelatedRecords.getInventoryRecords();

      final String prospectiveStatus = itemStatusFrom(
        loanAndRelatedRecords.getLoan(), loanAndRelatedRecords.getRequestQueue());

      if(inventoryRecords.isNotSameStatus(prospectiveStatus)) {
        return internalUpdate(inventoryRecords, prospectiveStatus)
          .thenApply(updatedItemResult ->
            updatedItemResult.map(loanAndRelatedRecords::withItem));
      }
      else {
        return skip(loanAndRelatedRecords);
      }
    }
    catch (Exception ex) {
      logException(ex);
      return CompletableFuture.completedFuture(
        HttpResult.failure(new ServerErrorFailure(ex)));
    }
  }

  public CompletableFuture<HttpResult<RequestAndRelatedRecords>> onRequestCreation(
    RequestAndRelatedRecords requestAndRelatedRecords) {

    try {
      RequestType requestType = RequestType.from(requestAndRelatedRecords.getRequest());

      RequestQueue requestQueue = requestAndRelatedRecords.getRequestQueue();

      String newStatus = requestQueue.hasOutstandingRequests()
        ? RequestType.from(requestQueue.getHighestPriorityRequest()).toCheckedOutItemStatus()
        : requestType.toCheckedOutItemStatus();

      if (requestAndRelatedRecords.getInventoryRecords().isNotSameStatus(newStatus)) {
        return internalUpdate(requestAndRelatedRecords.getInventoryRecords(), newStatus)
          .thenApply(updatedItemResult ->
            updatedItemResult.map(requestAndRelatedRecords::withItem));
      } else {
        return skip(requestAndRelatedRecords);
      }
    }
    catch (Exception ex) {
      logException(ex);
      return CompletableFuture.completedFuture(
        HttpResult.failure(new ServerErrorFailure(ex)));
    }
  }

  private CompletableFuture<HttpResult<JsonObject>> internalUpdate(
    InventoryRecords inventoryRecords,
    String newStatus) {

    CompletableFuture<HttpResult<JsonObject>> itemUpdated = new CompletableFuture<>();

    inventoryRecords.changeStatus(newStatus);

    this.itemsStorageClient.put(inventoryRecords.getItemId(),
      inventoryRecords.getItem(), putItemResponse -> {
        if(putItemResponse.getStatusCode() == 204) {
          itemUpdated.complete(HttpResult.success(inventoryRecords.getItem()));
        }
        else {
          itemUpdated.complete(HttpResult.failure(
            new ServerErrorFailure("Failed to update item")));
        }
      });

    return itemUpdated;
  }

  private <T> CompletableFuture<HttpResult<T>> skip(T previousResult) {
    return CompletableFuture.completedFuture(HttpResult.success(previousResult));
  }

  private String itemStatusFrom(Loan loan, RequestQueue requestQueue) {
    String prospectiveStatus;

    if(loan.isClosed()) {
      prospectiveStatus = requestQueue.hasOutstandingFulfillableRequests()
        ? RequestFulfilmentPreference.from(
          requestQueue.getHighestPriorityFulfillableRequest())
        .toCheckedInItemStatus()
        : AVAILABLE;
    }
    else {
      prospectiveStatus = requestQueue.hasOutstandingRequests()
        ? RequestType.from(requestQueue.getHighestPriorityRequest()).toCheckedOutItemStatus()
        : CHECKED_OUT;
    }

    return prospectiveStatus;
  }

  private void logException(Exception ex) {
    log.error("Exception occurred whilst updating item", ex);
  }
}
