package com.google.cloud.backend.spi;

import com.google.api.server.spi.response.NotFoundException;
import com.google.api.server.spi.response.UnauthorizedException;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.prospectivesearch.ProspectiveSearchService;
import com.google.appengine.api.prospectivesearch.ProspectiveSearchServiceFactory;
import com.google.appengine.api.users.User;
import com.google.cloud.backend.beans.EntityDto;
import com.google.cloud.backend.beans.EntityListDto;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Named;

/**
 * Utility class that provides CRUD operations for CloudEntities. Uses Search
 * API and Datastore as backend.
 *
 */
public class CrudOperations {

  private static final String CLOUD_ENTITY_ID_PREFIX = "CE:";

  private static final DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

  private static final ProspectiveSearchService prosSearch = ProspectiveSearchServiceFactory
      .getProspectiveSearchService();

  private static final MemcacheService memcache = MemcacheServiceFactory.getMemcacheService();

  private static final CrudOperations _instance = new CrudOperations();

  /**
   * Return the Singleton instance.
   */
  public static final CrudOperations getInstance() {
    return _instance;
  }

  private CrudOperations() {
  }

  /**
   * Saves all CloudEntities.
   *
   * @param cdl
   *          {@link EntityListDto} that contains the CloudEntities.
   * @param user
   *          {@link User} of the caller.
   * @return {@link EntityListDto} that contains the saved CloudEntities with
   *         auto-generated properties.
   * @throws UnauthorizedException
   */
  public EntityListDto saveAll(EntityListDto cdl, User user) throws UnauthorizedException {

    // find and update existing entities
    Map<String, Entity> existingEntities = findAndUpdateExistingEntities(cdl, user);

    // create new entities
    Set<Entity> newEntities = createNewEntities(cdl, user, existingEntities);

    // apply changes to Datastore
    Set<Entity> allEntities = new HashSet<Entity>();
    allEntities.addAll(newEntities);
    allEntities.addAll(existingEntities.values());
    datastore.put(allEntities);

    // update Memcache and ProsSearch
    Map<String, Entity> allEntitiesMap = new HashMap<String, Entity>();
    for (Entity e : allEntities) {

      // if it's a "private" entity, skip memcache and prossearch
      if (e.getKind().startsWith(SecurityChecker.KIND_PREFIX_PRIVATE)) {
        continue;
      }

      // apply changes to Memcache
      allEntitiesMap.put(e.getKey().getName(), e);
    }
    memcache.putAll(allEntitiesMap);

    // match with subscribers (date props converted to double)
    for (Entity e : allEntities) {
      convertDatePropertyToEpochTime(e, EntityDto.PROP_CREATED_AT);
      convertDatePropertyToEpochTime(e, EntityDto.PROP_UPDATED_AT);
      prosSearch.match(e, QueryOperations.PROS_SEARCH_DEFAULT_TOPIC);
    }

    // return a list of the updated EntityDto
    return cdl;
  }

  private void convertDatePropertyToEpochTime(Entity e, String propName) {
    Date d = (Date) e.getProperty(propName);
    e.setProperty(propName, Double.parseDouble(String.valueOf(d.getTime())));
  }

  private Map<String, Entity> findAndUpdateExistingEntities(EntityListDto cdl, User user)
      throws UnauthorizedException {

    // create a list of CEs with existing Id
    EntityListDto entitiesWithIds = new EntityListDto();
    Map<String, EntityDto> entitiesWithIdMap = new HashMap<String, EntityDto>();
    for (EntityDto cd : cdl.getEntries()) {
      if (cd.getId() != null) {
        entitiesWithIds.add(cd);
        entitiesWithIdMap.put(cd.getId(), cd);
      }
    }

    // try to get existing CEs
    Map<String, Entity> existingEntities = getAllEntitiesByKeyList(entitiesWithIds
        .readKeyList(user));

    // update existing entities
    for (String id : existingEntities.keySet()) {

      // check ACL
      Entity e = existingEntities.get(id);
      SecurityChecker.getInstance().checkAclForWrite(e, user);

      // update metadata
      EntityDto cd1 = entitiesWithIdMap.get(id);
      cd1.setUpdatedAt(new Date());
      if (user != null) {
        cd1.setUpdatedBy(user.getEmail());
      }

      // update the entity
      cd1.copyPropValuesToEntity(e);
    }
    return existingEntities;
  }

  /**
   * Returns a {@link Map} of CloudEntity IDs and {@link Entity}s for specified
   * {@link List} of {@link Key}s. It first tries to get them from Memcache, and
   * get from Datastore for entities not cached.
   *
   * @param keyList
   *          {@link List} of {@link Key}s
   * @return {@link Map} of CloudEntity ID and Entity.
   */
  protected Map<String, Entity> getAllEntitiesByKeyList(List<Key> keyList) {

    // try to get entities from Memcache
    List<String> idList = new LinkedList<String>();
    for (Key k : keyList) {
      idList.add(k.getName());
    }
    Map<String, Object> entities = memcache.getAll(idList);

    // build a list of Keys that have not found on Memcache
    List<Key> keysNotInMem = new LinkedList<Key>();
    for (Key k : keyList) {
      if (!entities.keySet().contains(k.getName())) {
        keysNotInMem.add(k);
      }
    }

    // get the rest of entities from Datastore
    if (!keysNotInMem.isEmpty()) {
      Map<Key, Entity> entitiesNotInMem = datastore.get(keysNotInMem);
      for (Entity e : entitiesNotInMem.values()) {
        entities.put(e.getKey().getName(), e);
      }
    }

    // cast to Entity
    Map<String, Entity> resultEntities = new HashMap<String, Entity>();
    for (String id : entities.keySet()) {
      resultEntities.put(id, (Entity) entities.get(id));
    }
    return resultEntities;
  }

  private Set<Entity> createNewEntities(EntityListDto cdl, User user,
      Map<String, Entity> existingEntities) {

    Set<Entity> newEntities = new HashSet<Entity>();
    for (EntityDto cd : cdl.getEntries()) {
      if (!existingEntities.containsKey(cd.getId())) {

        // check kindName
        String kindName = cd.getKindName();
        if (kindName == null || kindName.trim().length() == 0) {
          throw new IllegalArgumentException("save/saveAll: Kind Name not specified in a EntityDto");
        }

        // check if kindName is not the config kinds
        SecurityChecker.getInstance().checkIfKindNameAccessible(kindName);

        // create metadata
        cd.setCreatedAt(new Date());
        cd.setUpdatedAt(cd.getCreatedAt());
        cd.setId(CLOUD_ENTITY_ID_PREFIX + UUID.randomUUID().toString());

        // set security properties
        SecurityChecker.getInstance().setDefaultSecurityProps(cd, user);

        // create new Entity
        Entity e = new Entity(SecurityChecker.getInstance().createKeyWithNamespace(kindName,
            cd.getId(), user));
        cd.copyPropValuesToEntity(e);
        newEntities.add(e);
      }
    }
    return newEntities;
  }

  protected EntityDto getEntity(String kindName, String id, User user) throws NotFoundException {

    // get entity
    Entity e = getEntityById(kindName, id, user);

    // create EntityDto from the Entity
    return EntityDto.createFromEntity(e);
  }

  private Entity getEntityById(String kindName, String id, User user) throws NotFoundException {

    // try to find the Entity on Memcache
    Entity e = (Entity) memcache.get(id);

    // try to find the Entity
    if (e == null) {
      try {
        e = datastore.get(SecurityChecker.getInstance().createKeyWithNamespace(kindName, id, user));
      } catch (EntityNotFoundException e2) {
        throw new NotFoundException("Cloud Entity not found for id: " + id);
      }
    }
    return e;
  }

  protected EntityListDto getAllEntities(EntityListDto cdl, User user) {

    // get all entities by CbIdList
    Map<String, Entity> entities = getAllEntitiesByKeyList(cdl.readKeyList(user));

    // convert to CbDtos
    EntityListDto resultCdl = new EntityListDto();
    for (Entity e : entities.values()) {
      EntityDto cd = EntityDto.createFromEntity(e);
      resultCdl.getEntries().add(cd);
    }
    return resultCdl;
  }

  protected EntityDto delete(@Named("kind") String kindName, @Named("id") String id, User user)
      throws UnauthorizedException {

    // check ACL
    Entity e;
    try {
      e = getEntityById(kindName, id, user);
    } catch (NotFoundException e1) {
      return null; // if there's no such entity, just return null
    }
    SecurityChecker.getInstance().checkAclForWrite(e, user);

    // delete from memcache
    memcache.delete(id);

    // delete the CE
    datastore.delete(e.getKey());

    // return a EntityDto
    return EntityDto.createFromEntity(e);
  }

  protected EntityListDto deleteAll(EntityListDto cdl, User user) throws UnauthorizedException {

    // check ACL
    Map<String, Entity> entities = getAllEntitiesByKeyList(cdl.readKeyList(user));
    for (Entity e : entities.values()) {
      SecurityChecker.getInstance().checkAclForWrite(e, user);
    }

    // delete from memcache
    memcache.deleteAll(cdl.readIdList());

    // delete all the Entities
    datastore.delete(cdl.readKeyList(user));

    // return a dummy collection
    return new EntityListDto();
  }
}
