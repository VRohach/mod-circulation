package org.folio.circulation.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.isNull;

public class PeriodicForTenantsResource {

  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static PeriodicForTenantsResource instance;

  private ConcurrentHashMap<String, Long> tenantsContext;

  private PeriodicForTenantsResource() {
    tenantsContext = new ConcurrentHashMap<>();
  }

  public static PeriodicForTenantsResource getInstance(){
    if(isNull(instance)){
      instance = new PeriodicForTenantsResource();
    }
    return instance;
  }

  void addPeriodicIdForTenant(String tenant, long periodicId){
    if(isNull(tenantsContext.get(tenant))){
      log.info("Tenant with id: {} and {} sucesfully added", tenant, periodicId);
      tenantsContext.put(tenant, periodicId);
    }
  }

  public boolean isEmpty(){
    return tenantsContext.isEmpty();
  }

  public long getPeriodicByTenant(String tenant){
   return tenantsContext.get(tenant);
  }

  public boolean tenantExists(String tenantId){
    return tenantsContext.containsKey(tenantId);
  }
}
