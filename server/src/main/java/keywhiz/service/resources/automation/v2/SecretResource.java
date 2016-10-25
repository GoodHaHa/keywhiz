package keywhiz.service.resources.automation.v2;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import io.dropwizard.auth.Auth;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import keywhiz.api.automation.v2.CreateOrUpdateSecretRequestV2;
import keywhiz.api.automation.v2.CreateSecretRequestV2;
import keywhiz.api.automation.v2.ModifyGroupsRequestV2;
import keywhiz.api.automation.v2.SecretDetailResponseV2;
import keywhiz.api.automation.v2.SetSecretVersionRequestV2;
import keywhiz.api.model.AutomationClient;
import keywhiz.api.model.Group;
import keywhiz.api.model.SanitizedSecret;
import keywhiz.api.model.Secret;
import keywhiz.api.model.SecretSeriesAndContent;
import keywhiz.api.model.SecretVersion;
import keywhiz.log.AuditLog;
import keywhiz.log.Event;
import keywhiz.log.EventTag;
import keywhiz.service.daos.AclDAO;
import keywhiz.service.daos.AclDAO.AclDAOFactory;
import keywhiz.service.daos.GroupDAO;
import keywhiz.service.daos.GroupDAO.GroupDAOFactory;
import keywhiz.service.daos.SecretController;
import keywhiz.service.daos.SecretController.SecretBuilder;
import keywhiz.service.daos.SecretDAO;
import keywhiz.service.daos.SecretDAO.SecretDAOFactory;
import keywhiz.service.exceptions.ConflictException;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * @parentEndpointName automation/v2-secret-management
 * @resourceDescription Automation endpoints to manage secrets
 */
@Path("/automation/v2/secrets")
public class SecretResource {
  private static final Logger logger = LoggerFactory.getLogger(SecretResource.class);

  private final SecretController secretController;
  private final AclDAO aclDAO;
  private final GroupDAO groupDAO;
  private final SecretDAO secretDAO;
  private final AuditLog auditLog;

  @Inject public SecretResource(SecretController secretController, AclDAOFactory aclDAOFactory,
      GroupDAOFactory groupDAOFactory, SecretDAOFactory secretDAOFactory, AuditLog auditLog) {
    this.secretController = secretController;
    this.aclDAO = aclDAOFactory.readwrite();
    this.groupDAO = groupDAOFactory.readwrite();
    this.secretDAO = secretDAOFactory.readwrite();
    this.auditLog = auditLog;
  }

  /**
   * Creates a secret and assigns to given groups
   *
   * @excludeParams automationClient
   * @param request JSON request to create a secret
   *
   * @responseMessage 201 Created secret and assigned to given groups
   * @responseMessage 409 Secret already exists
   */
  @Timed @ExceptionMetered
  @POST
  @Consumes(APPLICATION_JSON)
  public Response createSecret(@Auth AutomationClient automationClient,
      @Valid CreateSecretRequestV2 request) {
    // allows new version, return version in resulting path
    String name = request.name();
    String user = automationClient.getName();

    SecretBuilder builder = secretController
        .builder(name, request.content(), automationClient.getName(), request.expiry())
        .withDescription(request.description())
        .withMetadata(request.metadata())
        .withType(request.type());

    Secret secret;
    try {
      secret = builder.create();
    } catch (DataAccessException e) {
      logger.info(format("Cannot create secret %s", name), e);
      throw new ConflictException(format("Cannot create secret %s.", name));
    }

    Map<String, String> extraInfo = new HashMap<>();
    if (request.description() != null) {
      extraInfo.put("description", request.description());
    }
    if (request.metadata() != null) {
      extraInfo.put("metadata", request.metadata().toString());
    }
    extraInfo.put("expiry", Long.toString(request.expiry()));
    auditLog.recordEvent(new Event(Instant.now(), EventTag.SECRET_CREATE, user, name, extraInfo));

    long secretId = secret.getId();
    groupsToGroupIds(request.groups())
        .forEach((maybeGroupId) -> maybeGroupId.ifPresent(
            (groupId) -> aclDAO.findAndAllowAccess(secretId, groupId, auditLog, user, new HashMap<>())));

    UriBuilder uriBuilder = UriBuilder.fromResource(SecretResource.class).path(name);

    return Response.created(uriBuilder.build()).build();
  }

  /**
   * Creates or updates (if it exists) a secret.
   *
   * @excludeParams automationClient
   * @param request JSON request to create a secret
   *
   * @responseMessage 201 Created secret and assigned to given groups
   */
  @Timed @ExceptionMetered
  @Path("{name}")
  @POST
  @Consumes(APPLICATION_JSON)
  public Response createOrUpdateSecret(@Auth AutomationClient automationClient,
                                       @PathParam("name") String name,
                                       @Valid CreateOrUpdateSecretRequestV2 request) {
    SecretBuilder builder = secretController
        .builder(name, request.content(), automationClient.getName(), request.expiry())
        .withDescription(request.description())
        .withMetadata(request.metadata())
        .withType(request.type());

    builder.createOrUpdate();

    Map<String, String> extraInfo = new HashMap<>();
    if (request.description() != null) {
      extraInfo.put("description", request.description());
    }
    if (request.metadata() != null) {
      extraInfo.put("metadata", request.metadata().toString());
    }
    extraInfo.put("expiry", Long.toString(request.expiry()));
    auditLog.recordEvent(new Event(Instant.now(), EventTag.SECRET_CREATEORUPDATE, automationClient.getName(), name, extraInfo));

    UriBuilder uriBuilder = UriBuilder.fromResource(SecretResource.class).path(name);

    return Response.created(uriBuilder.build()).build();
  }

  /**
   * Retrieve listing of secrets and metadata
   *
   * @excludeParams automationClient
   * @responseMessage 200 List of secrets and metadata
   */
  @Timed @ExceptionMetered
  @GET
  @Produces(APPLICATION_JSON)
  public Iterable<String> secretListing(@Auth AutomationClient automationClient) {
    return secretController.getSanitizedSecrets(null, null).stream()
        .map(SanitizedSecret::name)
        .collect(toSet());
  }

  /**
   * Retrieve listing of secrets expiring soon
   *
   * @excludeParams automationClient
   * @param time timestamp for farthest expiry to include
   *
   * @responseMessage 200 List of secrets expiring soon
   */
  @Timed @ExceptionMetered
  @Path("expiring/{time}")
  @GET
  @Produces(APPLICATION_JSON)
  public Iterable<String> secretListingExpiring(@Auth AutomationClient automationClient, @PathParam("time") Long time) {
    List<SanitizedSecret> secrets = secretController.getSanitizedSecrets(time, null);
    return secrets.stream()
        .map(SanitizedSecret::name)
        .collect(toList());
  }

  /**
   * Retrieve listing of secrets expiring soon
   *
   * @excludeParams automationClient
   * @param time timestamp for farthest expiry to include
   *
   * @responseMessage 200 List of secrets expiring soon
   */
  @Timed @ExceptionMetered
  @Path("expiring/v2/{time}")
  @GET
  @Produces(APPLICATION_JSON)
  public Iterable<SanitizedSecret> secretListingExpiringV2(@Auth AutomationClient automationClient, @PathParam("time") Long time) {
    List<SanitizedSecret> secrets = secretController.getSanitizedSecrets(time, null);
    return secrets.stream()
        .collect(toList());
  }

  /**
   * Backfill expiration for this secret.
   */
  @Timed @ExceptionMetered
  @Path("{name}/backfill-expiration")
  @POST
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  public boolean backfillExpiration(@Auth AutomationClient automationClient, @PathParam("name") String name, List<String> passwords) {
    Optional<Secret> secretOptional = secretController.getSecretByName(name);
    if (!secretOptional.isPresent()) {
      throw new NotFoundException("No such secret: " + name);
    }

    Secret secret = secretOptional.get();
    String secretName = secret.getName();
    byte[] secretContent = Base64.getDecoder().decode(secret.getSecret());

    // Always try empty password
    passwords.add("");

    Instant expiry = null;
    if (secretName.endsWith(".crt") || secretName.endsWith(".pem") || secretName.endsWith(".key")) {
      expiry = ExpirationExtractor.expirationFromEncodedCertificateChain(secretContent);
    } else if (secretName.endsWith(".gpg") || secretName.endsWith(".pgp")) {
      expiry = ExpirationExtractor.expirationFromOpenPGP(secretContent);
    } else if (secretName.endsWith(".p12") || secretName.endsWith(".pfx")) {
      while (expiry == null && !passwords.isEmpty()) {
        String password = passwords.remove(0);
        expiry = ExpirationExtractor.expirationFromKeystore("PKCS12", password, secretContent);
      }
    } else if (secretName.endsWith(".jceks")) {
      while (expiry == null && !passwords.isEmpty()) {
        String password = passwords.remove(0);
        expiry = ExpirationExtractor.expirationFromKeystore("JCEKS", password, secretContent);
      }
    } else if (secretName.endsWith(".jks")) {
      while (expiry == null && !passwords.isEmpty()) {
        String password = passwords.remove(0);
        expiry = ExpirationExtractor.expirationFromKeystore("JKS", password, secretContent);
      }
    }

    if (expiry != null) {
      logger.info("Found expiry for secret {}: {}", secretName, expiry.getEpochSecond());
      boolean success = secretDAO.setExpiration(name, expiry);
      if (success) {
        Map<String, String> extraInfo = new HashMap<>();
        extraInfo.put("backfilled expiry", Long.toString(expiry.getEpochSecond()));
        auditLog.recordEvent(new Event(Instant.now(), EventTag.SECRET_BACKFILLEXPIRY, automationClient.getName(), name, extraInfo));
      }
      return success;
    }

    logger.info("Unable to determine expiry for secret {}", secretName);
    return false;
  }

  /**
   * Retrieve listing of secrets expiring soon in a group
   *
   * @param time timestamp for farthest expiry to include
   * @param name Group name
   * @excludeParams automationClient
   * @responseMessage 200 List of secrets expiring soon in group
   */
  @Timed @ExceptionMetered
  @Path("expiring/{time}/{name}")
  @GET
  @Produces(APPLICATION_JSON)
  public Iterable<String> secretListingExpiringForGroup(@Auth AutomationClient automationClient,
      @PathParam("time") Long time, @PathParam("name") String name) {
    Group group = groupDAO.getGroup(name).orElseThrow(NotFoundException::new);

    List<SanitizedSecret> secrets = secretController.getSanitizedSecrets(time, group);
    return secrets.stream()
        .map(SanitizedSecret::name)
        .collect(toSet());
  }

  /**
   * Retrieve information on a secret series
   *
   * @excludeParams automationClient
   * @param name Secret series name
   *
   * @responseMessage 200 Secret series information retrieved
   * @responseMessage 404 Secret series not found
   */
  @Timed @ExceptionMetered
  @GET
  @Path("{name}")
  @Produces(APPLICATION_JSON)
  public SecretDetailResponseV2 secretInfo(@Auth AutomationClient automationClient,
      @PathParam("name") String name) {
    SecretSeriesAndContent secret = secretDAO.getSecretByName(name)
        .orElseThrow(NotFoundException::new);
    return SecretDetailResponseV2.builder()
        .series(secret.series())
        .expiry(secret.content().expiry())
        .build();
  }

  /**
   * Retrieve the given range of versions of this secret, sorted from newest to
   * oldest update time.  If versionIdx is nonzero, then numVersions versions,
   * starting from versionIdx in the list and increasing in index, will be
   * returned (set numVersions to a very large number to retrieve all versions).
   * For instance, versionIdx = 5 and numVersions = 10 will retrieve entries
   * at indices 5 through 14.
   *
   * @param name Secret series name
   * @param versionIdx The index in the list of versions of the first version to retrieve
   * @param numVersions The number of versions to retrieve
   * @excludeParams automationClient
   * @responseMessage 200 Secret series information retrieved
   * @responseMessage 404 Secret series not found
   */
  @Timed @ExceptionMetered
  @GET
  @Path("{name}/versions/{versionIdx}-{numVersions}")
  @Produces(APPLICATION_JSON)
  public Iterable<SecretDetailResponseV2> secretVersions(@Auth AutomationClient automationClient,
      @PathParam("name") String name, @PathParam("versionIdx") int versionIdx,
      @PathParam("numVersions") int numVersions) {
    ImmutableList<SecretVersion> versions =
        secretDAO.getSecretVersionsByName(name, versionIdx, numVersions)
            .orElseThrow(NotFoundException::new);

    return versions.stream()
        .map(v -> SecretDetailResponseV2.builder()
            .secretVersion(v)
            .build())
        .collect(toList());
  }


  /**
   * Reset the current version of the given secret to the given version index.
   *
   * @param request A request to update a given secret
   * @excludeParams automationClient
   * @responseMessage 201 Secret series current version updated successfully
   * @responseMessage 400 Invalid secret version specified
   * @responseMessage 404 Secret series not found
   */
  @Timed @ExceptionMetered
  @Path("{name}/setversion")
  @POST
  public Response resetSecretVersion(@Auth AutomationClient automationClient,
      @Valid SetSecretVersionRequestV2 request) {
    secretDAO.setCurrentSecretVersionByName(request.name(), request.version());

    // If the secret wasn't found or the request was misformed, setCurrentSecretVersionByName
    // already threw an exception
    Map<String, String> extraInfo = new HashMap<>();
    extraInfo.put("new version", Long.toString(request.version()));
    auditLog.recordEvent(new Event(Instant.now(), EventTag.SECRET_CHANGEVERSION, automationClient.getName(), request.name(), extraInfo));

    return Response.status(Response.Status.CREATED).build();
  }

  /**
   * Listing of groups a secret is assigned to
   *
   * @excludeParams automationClient
   * @param name Secret series name
   *
   * @responseMessage 200 Listing succeeded
   * @responseMessage 404 Secret series not found
   */
  @Timed @ExceptionMetered
  @GET
  @Path("{name}/groups")
  @Produces(APPLICATION_JSON)
  public Iterable<String> secretGroupsListing(@Auth AutomationClient automationClient,
      @PathParam("name") String name) {
    // TODO: Use latest version instead of non-versioned
    Secret secret = secretController.getSecretByName(name)
        .orElseThrow(NotFoundException::new);
    return aclDAO.getGroupsFor(secret).stream()
        .map(Group::getName)
        .collect(toSet());
  }

  /**
   * Modify the groups a secret is assigned to
   *
   * @excludeParams automationClient
   * @param name Secret series name
   * @param request JSON request to modify groups
   *
   * @responseMessage 201 Group membership changed
   * @responseMessage 404 Secret series not found
   */
  @Timed @ExceptionMetered
  @PUT
  @Path("{name}/groups")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  public Iterable<String> modifySecretGroups(@Auth AutomationClient automationClient,
      @PathParam("name") String name, @Valid ModifyGroupsRequestV2 request) {
    // TODO: Use latest version instead of non-versioned
    Secret secret = secretController.getSecretByName(name)
        .orElseThrow(NotFoundException::new);
    String user = automationClient.getName();

    long secretId = secret.getId();
    Set<String> oldGroups = aclDAO.getGroupsFor(secret).stream()
        .map(Group::getName)
        .collect(toSet());

    Set<String> groupsToAdd = Sets.difference(request.addGroups(), oldGroups);
    Set<String> groupsToRemove = Sets.intersection(request.removeGroups(), oldGroups);

    // TODO: should optimize AclDAO to use names and return only name column

    groupsToGroupIds(groupsToAdd)
        .forEach((maybeGroupId) -> maybeGroupId.ifPresent(
            (groupId) -> aclDAO.findAndAllowAccess(secretId, groupId, auditLog, user, new HashMap<>())));

    groupsToGroupIds(groupsToRemove)
        .forEach((maybeGroupId) -> maybeGroupId.ifPresent(
            (groupId) -> aclDAO.findAndRevokeAccess(secretId, groupId, auditLog, user, new HashMap<>())));

    return aclDAO.getGroupsFor(secret).stream()
        .map(Group::getName)
        .collect(toSet());
  }

  /**
   * Delete a secret series
   *
   * @excludeParams automationClient
   * @param name Secret series name
   *
   * @responseMessage 204 Secret series deleted
   * @responseMessage 404 Secret series not found
   */
  @Timed @ExceptionMetered
  @DELETE
  @Path("{name}")
  public Response deleteSecretSeries(@Auth AutomationClient automationClient,
      @PathParam("name") String name) {
    secretDAO.getSecretByName(name)
        .orElseThrow(NotFoundException::new);
    secretDAO.deleteSecretsByName(name);
    auditLog.recordEvent(new Event(Instant.now(), EventTag.SECRET_DELETE, automationClient.getName(), name));
    return Response.noContent().build();
  }

  private Stream<Optional<Long>> groupsToGroupIds(Set<String> groupNames) {
    return groupNames.stream()
        .map(groupDAO::getGroup)
        .map((group) -> group.map(Group::getId));
  }
}
