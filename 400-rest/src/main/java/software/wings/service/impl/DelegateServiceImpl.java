/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package software.wings.service.impl;

import static io.harness.annotations.dev.HarnessTeam.DEL;
import static io.harness.beans.FeatureName.DELEGATE_ENABLE_DYNAMIC_HANDLING_OF_REQUEST;
import static io.harness.beans.FeatureName.REDUCE_DELEGATE_MEMORY_SIZE;
import static io.harness.beans.FeatureName.USE_IMMUTABLE_DELEGATE;
import static io.harness.configuration.DeployVariant.DEPLOY_VERSION;
import static io.harness.data.structure.EmptyPredicate.isEmpty;
import static io.harness.data.structure.EmptyPredicate.isNotEmpty;
import static io.harness.data.structure.UUIDGenerator.convertFromBase64;
import static io.harness.data.structure.UUIDGenerator.generateUuid;
import static io.harness.delegate.beans.DelegateType.CE_KUBERNETES;
import static io.harness.delegate.beans.DelegateType.DOCKER;
import static io.harness.delegate.beans.DelegateType.ECS;
import static io.harness.delegate.beans.DelegateType.HELM_DELEGATE;
import static io.harness.delegate.beans.DelegateType.KUBERNETES;
import static io.harness.delegate.beans.DelegateType.SHELL_SCRIPT;
import static io.harness.delegate.beans.K8sPermissionType.NAMESPACE_ADMIN;
import static io.harness.delegate.message.ManagerMessageConstants.MIGRATE;
import static io.harness.delegate.message.ManagerMessageConstants.SELF_DESTRUCT;
import static io.harness.delegate.utils.DelegateTelemetryConstants.DELEGATE_CREATED_EVENT;
import static io.harness.delegate.utils.DelegateTelemetryConstants.DELEGATE_REGISTERED_EVENT;
import static io.harness.eraro.ErrorCode.USAGE_LIMITS_EXCEEDED;
import static io.harness.eventsframework.EventsFrameworkMetadataConstants.DELETE_ACTION;
import static io.harness.exception.WingsException.USER;
import static io.harness.k8s.KubernetesConvention.getAccountIdentifier;
import static io.harness.logging.AutoLogContext.OverrideBehavior.OVERRIDE_NESTS;
import static io.harness.metrics.impl.DelegateMetricsServiceImpl.DELEGATE_DESTROYED;
import static io.harness.metrics.impl.DelegateMetricsServiceImpl.DELEGATE_DISCONNECTED;
import static io.harness.metrics.impl.DelegateMetricsServiceImpl.DELEGATE_REGISTRATION_FAILED;
import static io.harness.metrics.impl.DelegateMetricsServiceImpl.DELEGATE_RESTARTED;
import static io.harness.metrics.impl.DelegateMetricsServiceImpl.IMMUTABLE_DELEGATES;
import static io.harness.metrics.impl.DelegateMetricsServiceImpl.MUTABLE_DELEGATES;
import static io.harness.mongo.MongoUtils.setUnset;
import static io.harness.obfuscate.Obfuscator.obfuscate;
import static io.harness.persistence.HQuery.excludeAuthority;

import static software.wings.audit.AuditHeader.Builder.anAuditHeader;
import static software.wings.beans.CGConstants.GLOBAL_APP_ID;
import static software.wings.beans.DelegateSequenceConfig.Builder.aDelegateSequenceBuilder;
import static software.wings.beans.Event.Builder.anEvent;
import static software.wings.beans.User.Builder.anUser;
import static software.wings.utils.Utils.normalizeIdentifier;
import static software.wings.utils.Utils.uuidToIdentifier;

import static freemarker.template.Configuration.VERSION_2_3_23;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofMinutes;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Comparator.comparingInt;
import static java.util.Comparator.naturalOrder;
import static java.util.Objects.nonNull;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.compare;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.substringAfter;
import static org.apache.commons.lang3.StringUtils.substringBefore;
import static org.mongodb.morphia.mapping.Mapper.ID_KEY;

import io.harness.agent.beans.AgentMtlsEndpointDetails;
import io.harness.annotations.dev.BreakDependencyOn;
import io.harness.annotations.dev.HarnessModule;
import io.harness.annotations.dev.OwnedBy;
import io.harness.annotations.dev.TargetModule;
import io.harness.beans.DelegateHeartbeatResponseStreaming;
import io.harness.beans.DelegateHeartbeatResponseStreaming.DelegateHeartbeatResponseStreamingBuilder;
import io.harness.beans.DelegateTask;
import io.harness.beans.EmbeddedUser;
import io.harness.beans.PageRequest;
import io.harness.beans.PageRequest.PageRequestBuilder;
import io.harness.beans.PageResponse;
import io.harness.beans.SearchFilter.Operator;
import io.harness.capability.CapabilityRequirement;
import io.harness.capability.service.CapabilityService;
import io.harness.configuration.DeployMode;
import io.harness.configuration.DeployVariant;
import io.harness.data.structure.EmptyPredicate;
import io.harness.delegate.beans.AvailableDelegateSizes;
import io.harness.delegate.beans.ConnectionMode;
import io.harness.delegate.beans.Delegate;
import io.harness.delegate.beans.Delegate.DelegateKeys;
import io.harness.delegate.beans.DelegateApproval;
import io.harness.delegate.beans.DelegateApprovalResponse;
import io.harness.delegate.beans.DelegateConfiguration;
import io.harness.delegate.beans.DelegateConnectionDetails;
import io.harness.delegate.beans.DelegateConnectionHeartbeat;
import io.harness.delegate.beans.DelegateDTO;
import io.harness.delegate.beans.DelegateEntityOwner;
import io.harness.delegate.beans.DelegateGroup;
import io.harness.delegate.beans.DelegateGroup.DelegateGroupKeys;
import io.harness.delegate.beans.DelegateGroupStatus;
import io.harness.delegate.beans.DelegateInitializationDetails;
import io.harness.delegate.beans.DelegateInstanceStatus;
import io.harness.delegate.beans.DelegateParams;
import io.harness.delegate.beans.DelegateProfile;
import io.harness.delegate.beans.DelegateProfileParams;
import io.harness.delegate.beans.DelegateRegisterResponse;
import io.harness.delegate.beans.DelegateResponseData;
import io.harness.delegate.beans.DelegateScripts;
import io.harness.delegate.beans.DelegateSelector;
import io.harness.delegate.beans.DelegateSetupDetails;
import io.harness.delegate.beans.DelegateSizeDetails;
import io.harness.delegate.beans.DelegateTags;
import io.harness.delegate.beans.DelegateTokenDetails;
import io.harness.delegate.beans.DelegateTokenStatus;
import io.harness.delegate.beans.DelegateType;
import io.harness.delegate.beans.DelegateUnregisterRequest;
import io.harness.delegate.beans.DuplicateDelegateException;
import io.harness.delegate.beans.FileBucket;
import io.harness.delegate.beans.FileMetadata;
import io.harness.delegate.beans.K8sConfigDetails;
import io.harness.delegate.beans.executioncapability.ExecutionCapability;
import io.harness.delegate.events.DelegateGroupDeleteEvent;
import io.harness.delegate.events.DelegateGroupUpsertEvent;
import io.harness.delegate.events.DelegateRegisterEvent;
import io.harness.delegate.events.DelegateUnregisterEvent;
import io.harness.delegate.service.DelegateVersionService;
import io.harness.delegate.service.intfc.DelegateNgTokenService;
import io.harness.delegate.task.DelegateLogContext;
import io.harness.delegate.telemetry.DelegateTelemetryPublisher;
import io.harness.delegate.utils.DelegateEntityOwnerHelper;
import io.harness.environment.SystemEnvironment;
import io.harness.event.handler.impl.EventPublishHelper;
import io.harness.eventsframework.EventsFrameworkConstants;
import io.harness.eventsframework.EventsFrameworkMetadataConstants;
import io.harness.eventsframework.api.Producer;
import io.harness.eventsframework.entity_crud.EntityChangeDTO;
import io.harness.eventsframework.producer.Message;
import io.harness.exception.GeneralException;
import io.harness.exception.InvalidArgumentsException;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.LimitsExceededException;
import io.harness.exception.UnexpectedException;
import io.harness.expression.ExpressionEvaluator;
import io.harness.ff.FeatureFlagService;
import io.harness.globalcontex.DelegateTokenGlobalContextData;
import io.harness.grpc.DelegateServiceClassicGrpcClient;
import io.harness.k8s.model.response.CEK8sDelegatePrerequisite;
import io.harness.lock.AcquiredLock;
import io.harness.lock.PersistentLocker;
import io.harness.logging.AutoLogContext;
import io.harness.logging.Misc;
import io.harness.manage.GlobalContextManager;
import io.harness.metrics.intfc.DelegateMetricsService;
import io.harness.network.Http;
import io.harness.ng.core.utils.NGUtils;
import io.harness.observer.RemoteObserverInformer;
import io.harness.observer.Subject;
import io.harness.outbox.api.OutboxService;
import io.harness.persistence.HIterator;
import io.harness.persistence.HPersistence;
import io.harness.persistence.UuidAware;
import io.harness.reflection.ReflectionUtils;
import io.harness.serializer.JsonUtils;
import io.harness.serializer.KryoSerializer;
import io.harness.service.intfc.AgentMtlsEndpointService;
import io.harness.service.intfc.DelegateCache;
import io.harness.service.intfc.DelegateCallbackRegistry;
import io.harness.service.intfc.DelegateInsightsService;
import io.harness.service.intfc.DelegateProfileObserver;
import io.harness.service.intfc.DelegateSetupService;
import io.harness.service.intfc.DelegateSyncService;
import io.harness.service.intfc.DelegateTaskSelectorMapService;
import io.harness.service.intfc.DelegateTaskService;
import io.harness.service.intfc.DelegateTokenService;
import io.harness.stream.BoundedInputStream;
import io.harness.validation.SuppressValidation;
import io.harness.version.VersionInfoManager;
import io.harness.waiter.WaitNotifyEngine;

import software.wings.app.DelegateGrpcConfig;
import software.wings.app.MainConfiguration;
import software.wings.audit.AuditHeader;
import software.wings.beans.Account;
import software.wings.beans.CEDelegateStatus;
import software.wings.beans.CEDelegateStatus.CEDelegateStatusBuilder;
import software.wings.beans.DelegateConnection;
import software.wings.beans.DelegateScalingGroup;
import software.wings.beans.DelegateSequenceConfig;
import software.wings.beans.DelegateSequenceConfig.DelegateSequenceConfigKeys;
import software.wings.beans.DelegateStatus;
import software.wings.beans.Event.Type;
import software.wings.beans.HttpMethod;
import software.wings.beans.alert.AlertType;
import software.wings.beans.alert.DelegatesDownAlert;
import software.wings.cdn.CdnConfig;
import software.wings.common.AuditHelper;
import software.wings.core.managerConfiguration.ConfigurationController;
import software.wings.expression.SecretFunctor;
import software.wings.features.DelegatesFeature;
import software.wings.features.api.UsageLimitedFeature;
import software.wings.helpers.ext.mail.EmailData;
import software.wings.helpers.ext.url.SubdomainUrlHelperIntfc;
import software.wings.jre.JreConfig;
import software.wings.licensing.LicenseService;
import software.wings.service.impl.EventEmitter.Channel;
import software.wings.service.impl.TemplateParameters.TemplateParametersBuilder;
import software.wings.service.impl.infra.InfraDownloadService;
import software.wings.service.intfc.AccountService;
import software.wings.service.intfc.AlertService;
import software.wings.service.intfc.AssignDelegateService;
import software.wings.service.intfc.ConfigService;
import software.wings.service.intfc.DelegateProfileService;
import software.wings.service.intfc.DelegateSelectionLogsService;
import software.wings.service.intfc.DelegateService;
import software.wings.service.intfc.DelegateTaskServiceClassic;
import software.wings.service.intfc.EmailNotificationService;
import software.wings.service.intfc.FileService;
import software.wings.service.intfc.SettingsService;
import software.wings.service.intfc.security.ManagerDecryptionService;
import software.wings.service.intfc.security.SecretManager;

import com.github.zafarkhaja.semver.Version;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.google.protobuf.StringValue;
import com.mongodb.DuplicateKeyException;
import com.mongodb.MongoGridFSException;
import freemarker.cache.ClassTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import io.dropwizard.jersey.validation.JerseyViolationException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import javax.validation.ConstraintViolation;
import javax.validation.executable.ValidateOnExecution;
import javax.ws.rs.core.MediaType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request.Builder;
import okhttp3.Response;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.atmosphere.cpr.BroadcasterFactory;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.hibernate.validator.internal.engine.path.PathImpl;
import org.jetbrains.annotations.NotNull;
import org.mongodb.morphia.Key;
import org.mongodb.morphia.query.Query;
import org.mongodb.morphia.query.UpdateOperations;

@Singleton
@ValidateOnExecution
// For testing since this class should probably be 20 classes, and it's hard to test it
@lombok.Builder
// For guice injection (since we wrongly use field injection, but this is not fixable for class at this size
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
@TargetModule(HarnessModule._420_DELEGATE_SERVICE)
@BreakDependencyOn("software.wings.beans.Event")
@BreakDependencyOn("software.wings.beans.Account")
@BreakDependencyOn("io.harness.event.handler.impl.EventPublishHelper")
@OwnedBy(DEL)
public class DelegateServiceImpl implements DelegateService {
  /**
   * The constant DELEGATE_DIR.
   */
  private static final String HARNESS_DELEGATE = "harness-delegate";
  private static final String HARNESS_NG_DELEGATE_NAMESPACE = "harness-delegate-ng";
  private static final String HARNESS_NG_DELEGATE = "harness-ng-delegate";
  public static final String DELEGATE_DIR = HARNESS_DELEGATE;
  public static final String DOCKER_DELEGATE = HARNESS_DELEGATE + "-docker";
  public static final String KUBERNETES_DELEGATE = HARNESS_DELEGATE + "-kubernetes";
  public static final String ECS_DELEGATE = HARNESS_DELEGATE + "-ecs";
  private static final Configuration templateConfiguration = new Configuration(VERSION_2_3_23);
  private static final String HARNESS_ECS_DELEGATE = "Harness-ECS-Delegate";
  private static final String DELIMITER = "_";
  private static final int MAX_RETRIES = 2;
  private static final String NG_CLUSTER_ADMIN_YAML = "-ng-cluster-admin.yaml.ftl";
  private static final String NG_CLUSTER_VIEWER_YAML = "-ng-cluster-viewer.yaml.ftl";
  private static final String NG_NAMESPACE_ADMIN_YAML = "-ng-namespace-admin.yaml.ftl";
  private static final String IMMUTABLE_DELEGATE_YAML = "harness-delegate-ng-immutable.yaml.ftl";
  private static final String IMMUTABLE_CG_DELEGATE_YAML = "harness-delegate-immutable.yaml.ftl";

  public static final String HARNESS_DELEGATE_VALUES_YAML = HARNESS_DELEGATE + "-values";
  private static final String YAML = ".yaml";
  private static final String UPGRADE_VERSION = "upgradeVersion";
  private static final String STREAM_DELEGATE = "/stream/delegate/";
  private static final String TAR_GZ = ".tar.gz";
  private static final String README = "README";
  private static final String README_TXT = "/README.txt";
  private static final String EMPTY_VERSION = "0.0.0";
  private static final String JRE_DIRECTORY = "jreDirectory";
  private static final String JRE_MAC_DIRECTORY = "jreMacDirectory";
  private static final String JRE_TAR_PATH = "jreTarPath";
  private static final String ALPN_JAR_PATH = "alpnJarPath";
  private static final String JRE_VERSION_KEY = "jreVersion";
  private static final String ENV_ENV_VAR = "ENV";
  private static final String SAMPLE_DELEGATE_NAME = "harness-sample-k8s-delegate";
  private static final String deployVersion = System.getenv(DEPLOY_VERSION);
  private static final String DELEGATES_UPDATED_RESPONSE = "Following delegates have been updated";
  private static final String NO_DELEGATES_UPDATED_RESPONSE = "No delegate is waiting for approval/rejection";

  private static final long MAX_GRPC_HB_TIMEOUT = TimeUnit.MINUTES.toMillis(15);

  private static final Duration HEARTBEAT_EXPIRY_TIME = ofMinutes(5);

  static {
    templateConfiguration.setTemplateLoader(new ClassTemplateLoader(DelegateServiceImpl.class, "/delegatetemplates"));
  }

  @Inject private HPersistence persistence;
  @Inject private WaitNotifyEngine waitNotifyEngine;
  @Inject private AccountService accountService;
  @Inject private LicenseService licenseService;
  @Inject private MainConfiguration mainConfiguration;
  @Inject private EventEmitter eventEmitter;
  @Inject private BroadcasterFactory broadcasterFactory;
  @Inject private AssignDelegateService assignDelegateService;
  @Inject private AlertService alertService;
  @Inject private Clock clock;
  @Inject private VersionInfoManager versionInfoManager;
  @Inject private FeatureFlagService featureFlagService;
  @Inject private InfraDownloadService infraDownloadService;
  @Inject private DelegateProfileService delegateProfileService;
  @Inject private ManagerDecryptionService managerDecryptionService;
  @Inject private SecretManager secretManager;
  @Inject private ExpressionEvaluator evaluator;
  @Inject private FileService fileService;
  @Inject private EventPublishHelper eventPublishHelper;
  @Inject private ConfigService configService;
  @Inject private PersistentLocker persistentLocker;
  @Inject private DelegateTaskBroadcastHelper broadcastHelper;
  @Inject private AuditServiceHelper auditServiceHelper;
  @Inject private SubdomainUrlHelperIntfc subdomainUrlHelper;
  @Inject private ConfigurationController configurationController;
  @Inject private DelegateSelectionLogsService delegateSelectionLogsService;
  @Inject private DelegateConnectionDao delegateConnectionDao;
  @Inject private SystemEnvironment sysenv;
  @Inject private DelegateSyncService delegateSyncService;
  @Inject private DelegateTaskService delegateTaskService;
  @Inject private KryoSerializer kryoSerializer;
  @Inject private DelegateCallbackRegistry delegateCallbackRegistry;
  @Inject private EmailNotificationService emailNotificationService;
  @Inject private DelegateGrpcConfig delegateGrpcConfig;
  @Inject private DelegateTaskSelectorMapService taskSelectorMapService;
  @Inject private SettingsService settingsService;
  @Inject private DelegateCache delegateCache;
  @Inject private CapabilityService capabilityService;
  @Inject private DelegateInsightsService delegateInsightsService;
  @Inject private DelegateSetupService delegateSetupService;
  @Inject private AuditHelper auditHelper;
  @Inject private DelegateTokenService delegateTokenService;
  @Inject private DelegateTaskServiceClassic delegateTaskServiceClassic;
  @Inject private DelegateTelemetryPublisher delegateTelemetryPublisher;
  @Inject @Named(EventsFrameworkConstants.ENTITY_CRUD) private Producer eventProducer;

  @Inject @Named(DelegatesFeature.FEATURE_NAME) private UsageLimitedFeature delegatesFeature;
  @Inject @Getter private Subject<DelegateObserver> subject = new Subject<>();
  @Getter private final Subject<DelegateProfileObserver> delegateProfileSubject = new Subject<>();
  @Inject
  @Getter(onMethod = @__(@SuppressValidation))
  private Subject<DelegateTaskStatusObserver> delegateTaskStatusObserverSubject;
  @Inject private OutboxService outboxService;
  @Inject private DelegateServiceClassicGrpcClient delegateServiceClassicGrpcClient;
  @Inject private DelegateNgTokenService delegateNgTokenService;
  @Inject private RemoteObserverInformer remoteObserverInformer;
  @Inject private DelegateMetricsService delegateMetricsService;
  @Inject private DelegateVersionService delegateVersionService;
  @Inject private AgentMtlsEndpointService agentMtlsEndpointService;

  private final LoadingCache<String, String> delegateVersionCache =
      CacheBuilder.newBuilder()
          .maximumSize(10000)
          .expireAfterWrite(1, TimeUnit.MINUTES)
          .build(new CacheLoader<String, String>() {
            @Override
            public String load(@NotNull String accountId) {
              return fetchDelegateMetadataFromStorage();
            }
          });

  @Override
  public List<Integer> getCountOfDelegatesForAccounts(List<String> accountIds) {
    List<Delegate> delegates = persistence.createQuery(Delegate.class)
                                   .field(DelegateKeys.accountId)
                                   .in(accountIds)
                                   .field(DelegateKeys.status)
                                   .notEqual(DelegateInstanceStatus.DELETED)
                                   .asList();
    Map<String, Integer> countOfDelegatesPerAccount =
        accountIds.stream().collect(toMap(accountId -> accountId, accountId -> 0));
    delegates.forEach(delegate -> {
      int currentCount = countOfDelegatesPerAccount.get(delegate.getAccountId());
      countOfDelegatesPerAccount.put(delegate.getAccountId(), currentCount + 1);
    });
    return accountIds.stream().map(countOfDelegatesPerAccount::get).collect(toList());
  }

  @Override
  public PageResponse<Delegate> list(PageRequest<Delegate> pageRequest) {
    return persistence.query(Delegate.class, pageRequest);
  }

  @Override
  public List<String> getKubernetesDelegateNames(String accountId) {
    return persistence.createQuery(Delegate.class)
        .filter(DelegateKeys.accountId, accountId)
        .field(DelegateKeys.delegateName)
        .exists()
        .project(DelegateKeys.delegateName, true)
        .asList()
        .stream()
        .map(Delegate::getDelegateName)
        .distinct()
        .sorted(naturalOrder())
        .collect(toList());
  }

  @Override
  public CEDelegateStatus validateCEDelegate(String accountId, String delegateName) {
    Delegate delegate = persistence.createQuery(Delegate.class)
                            .filter(DelegateKeys.accountId, accountId)
                            .field(DelegateKeys.delegateName)
                            .exists()
                            .filter(DelegateKeys.delegateName, delegateName)
                            .get();

    if (delegate == null) {
      return CEDelegateStatus.builder().found(false).build();
    }

    CEDelegateStatusBuilder ceDelegateStatus = CEDelegateStatus.builder()
                                                   .found(true)
                                                   .ceEnabled(delegate.isCeEnabled())
                                                   .delegateName(delegate.getDelegateName())
                                                   .delegateType(delegate.getDelegateType())
                                                   .uuid(delegate.getUuid())
                                                   .lastHeartBeat(delegate.getLastHeartBeat())
                                                   .status(delegate.getStatus())
                                                   .build()
                                                   .toBuilder();

    // check delegate connections, if it's active
    List<DelegateConnectionDetails> activelyConnectedDelegates =
        delegateConnectionDao.list(accountId, delegate.getUuid())
            .stream()
            .map(delegateConnection
                -> DelegateConnectionDetails.builder()
                       .uuid(delegateConnection.getUuid())
                       .lastHeartbeat(delegateConnection.getLastHeartbeat())
                       .version(delegateConnection.getVersion())
                       .build())
            .collect(toList());
    if (activelyConnectedDelegates.isEmpty()) {
      return ceDelegateStatus.build();
    }

    // verify metrics server and ce permissions
    final CEK8sDelegatePrerequisite cek8sDelegatePrerequisite =
        settingsService.validateCEDelegateSetting(accountId, delegateName);

    return ceDelegateStatus.connections(activelyConnectedDelegates)
        .metricsServerCheck(cek8sDelegatePrerequisite.getMetricsServer())
        .permissionRuleList(cek8sDelegatePrerequisite.getPermissions())
        .build();
  }

  @Override
  public Set<String> getAllDelegateSelectors(String accountId) {
    Query<Delegate> delegateQuery =
        persistence.createQuery(Delegate.class)
            .filter(DelegateKeys.accountId, accountId)
            .field(DelegateKeys.ng)
            .notEqual(true) // notEqual is required to cover all existing delegates that will not have the ng flag set
            .field(DelegateKeys.status)
            .notEqual(DelegateInstanceStatus.DELETED)
            .project(DelegateKeys.accountId, true)
            .project(DelegateKeys.tags, true)
            .project(DelegateKeys.delegateName, true)
            .project(DelegateKeys.hostName, true)
            .project(DelegateKeys.delegateProfileId, true)
            .project(DelegateKeys.delegateGroupId, true);

    try (HIterator<Delegate> delegates = new HIterator<>(delegateQuery.fetch())) {
      if (delegates.hasNext()) {
        Set<String> selectors = new HashSet<>();

        for (Delegate delegate : delegates) {
          selectors.addAll(retrieveDelegateSelectors(delegate, false));
        }
        return selectors;
      }
    }
    return emptySet();
  }

  @Override
  public Set<String> getAllDelegateSelectorsUpTheHierarchy(
      final String accountId, final String orgId, final String projectId) {
    final Query<DelegateGroup> delegateGroupQuery = persistence.createQuery(DelegateGroup.class)
                                                        .filter(DelegateGroupKeys.accountId, accountId)
                                                        .filter(DelegateGroupKeys.ng, true);

    final DelegateEntityOwner owner = DelegateEntityOwnerHelper.buildOwner(orgId, projectId);

    delegateGroupQuery.field(DelegateKeys.owner_identifier)
        .in(Arrays.asList(null, orgId, owner != null ? owner.getIdentifier() : null));

    final List<DelegateGroup> delegateGroups =
        delegateGroupQuery.field(DelegateGroupKeys.status).notEqual(DelegateGroupStatus.DELETED).asList();

    return delegateGroups.stream()
        .map(group -> {
          Set<String> groupSelectors =
              new HashSet<>(delegateSetupService.retrieveDelegateGroupImplicitSelectors(group).keySet());

          if (isNotEmpty(group.getTags())) {
            groupSelectors.addAll(group.getTags());
          }

          return groupSelectors;
        })
        .flatMap(Collection::stream)
        .collect(toSet());
  }
  @Override
  public List<DelegateSelector> getAllDelegateSelectorsUpTheHierarchyV2(
      final String accountId, final String orgId, final String projectId) {
    final Query<DelegateGroup> delegateGroupQuery = persistence.createQuery(DelegateGroup.class)
                                                        .filter(DelegateGroupKeys.accountId, accountId)
                                                        .filter(DelegateGroupKeys.ng, true);

    final DelegateEntityOwner owner = DelegateEntityOwnerHelper.buildOwner(orgId, projectId);

    delegateGroupQuery.field(DelegateKeys.owner_identifier)
        .in(Arrays.asList(null, orgId, owner != null ? owner.getIdentifier() : null));

    final List<DelegateGroup> delegateGroups =
        delegateGroupQuery.field(DelegateGroupKeys.status).notEqual(DelegateGroupStatus.DELETED).asList();

    Map<String, DelegateSelector> delegateSelectorMap = new HashMap<>();
    delegateGroups.forEach(group -> {
      boolean isConnected = isDelegateGroupConnected(delegateCache.getDelegatesForGroup(accountId, group.getUuid()));

      // Add implicit selectors to map.
      delegateSetupService.retrieveDelegateGroupImplicitSelectors(group).keySet().forEach(
          implicitSelector -> addDelegateSelector(implicitSelector, delegateSelectorMap, isConnected));

      // Add group tags to map.
      if (isNotEmpty(group.getTags())) {
        group.getTags().forEach(tag -> addDelegateSelector(tag, delegateSelectorMap, isConnected));
      }
    });
    return delegateSelectorMap.values().stream().sorted(new DelegateSelectorComparator()).collect(Collectors.toList());
  }

  private boolean isDelegateGroupConnected(List<Delegate> delegateList) {
    if (isNotEmpty(delegateList)) {
      return delegateList.stream().anyMatch(this::checkHeartBeatExpiration);
    } else {
      log.warn("unable to get delegate list for group from cache");
      return false;
    }
  }

  private void addDelegateSelector(
      String selector, Map<String, DelegateSelector> delegateSelectorMap, boolean isConnected) {
    // Add to map if: 1) the selector is not present. 2) selector is connected.
    if (!delegateSelectorMap.containsKey(selector) || isConnected) {
      delegateSelectorMap.put(selector, new DelegateSelector(selector, isConnected));
    }
  }

  private boolean checkHeartBeatExpiration(Delegate delegate) {
    return delegate.getLastHeartBeat() > System.currentTimeMillis() - HEARTBEAT_EXPIRY_TIME.toMillis();
  }

  @Override
  public Set<String> retrieveDelegateSelectors(Delegate delegate, boolean fetchFromCache) {
    Set<String> selectors = delegate.getTags() == null ? new HashSet<>() : new HashSet<>(delegate.getTags());
    if (delegate.isNg()) {
      DelegateGroup delegateGroup = fetchFromCache
          ? delegateCache.getDelegateGroup(delegate.getAccountId(), delegate.getDelegateGroupId())
          : delegateSetupService.getDelegateGroup(delegate.getAccountId(), delegate.getDelegateGroupId());
      if (delegateGroup != null && delegateGroup.getTags() != null) {
        selectors.addAll(delegateGroup.getTags());
      }
    }
    selectors.addAll(delegateSetupService.retrieveDelegateImplicitSelectors(delegate, fetchFromCache).keySet());

    return selectors;
  }

  @Override
  public List<String> getAvailableVersions(String accountId) {
    DelegateStatus status = getDelegateStatus(accountId);
    return status.getPublishedVersions();
  }

  @Override
  public Double getConnectedRatioWithPrimary(String targetVersion, String accountId, String ringName) {
    targetVersion = Arrays.stream(targetVersion.split("-")).findFirst().get();

    List<String> delegateVersions;
    if (isNotEmpty(ringName)) {
      delegateVersions = delegateVersionService.getDelegateJarVersions(ringName, accountId);
    } else {
      String primaryDelegateForAccount = StringUtils.isEmpty(accountId) ? Account.GLOBAL_ACCOUNT_ID : accountId;
      DelegateConfiguration delegateConfiguration = accountService.getDelegateConfiguration(primaryDelegateForAccount);
      delegateVersions = delegateConfiguration.getDelegateVersions();
    }

    String primaryVersion = delegateVersions.get(0).split("-")[0];
    long primary = delegateConnectionDao.numberOfActiveDelegateConnectionsPerVersion(primaryVersion, accountId);

    // If we do not have any delegates in the primary version, lets unblock the deployment,
    // that will be very rare and we are in trouble anyways, let report 1 to let the new deployment go.
    if (primary == 0) {
      return 1.0;
    }

    long target = delegateConnectionDao.numberOfActiveDelegateConnectionsPerVersion(targetVersion, accountId);
    return BigDecimal.valueOf((double) target / (double) primary).setScale(3, RoundingMode.HALF_UP).doubleValue();
  }

  @Override
  public Double getConnectedDelegatesRatio(String version, String accountId) {
    long totalDelegatesWithVersion = delegateConnectionDao.numberOfDelegateConnectionsPerVersion(version, accountId);
    if (totalDelegatesWithVersion == 0) {
      return 0.0;
    }
    long connectedDelegatesWithVersion =
        delegateConnectionDao.numberOfActiveDelegateConnectionsPerVersion(version, accountId);
    return BigDecimal.valueOf((double) connectedDelegatesWithVersion / (double) totalDelegatesWithVersion)
        .setScale(3, RoundingMode.HALF_UP)
        .doubleValue();
  }

  @Override
  public Map<String, List<String>> getActiveDelegatesPerAccount(String version) {
    version = Arrays.stream(version.split("-")).findFirst().get();
    return delegateConnectionDao.obtainActiveDelegatesPerAccount(version);
  }

  @Override
  public DelegateSetupDetails validateKubernetesSetupDetails(
      String accountId, DelegateSetupDetails delegateSetupDetails) {
    if (null == delegateSetupDetails) {
      throw new InvalidRequestException("Delegate Setup Details must be provided.", USER);
    }
    validateDelegateProfileId(accountId, delegateSetupDetails.getDelegateConfigurationId());

    if (isBlank(delegateSetupDetails.getName())) {
      throw new InvalidRequestException("Delegate Name must be provided.", USER);
    }
    checkUniquenessOfDelegateName(accountId, delegateSetupDetails.getName(), true);
    if (delegateSetupDetails.getSize() == null) {
      throw new InvalidRequestException("Delegate Size must be provided.", USER);
    }

    K8sConfigDetails k8sConfigDetails = delegateSetupDetails.getK8sConfigDetails();
    if (k8sConfigDetails == null || k8sConfigDetails.getK8sPermissionType() == null) {
      throw new InvalidRequestException("K8s permission type must be provided.", USER);
    } else if (k8sConfigDetails.getK8sPermissionType() == NAMESPACE_ADMIN && isBlank(k8sConfigDetails.getNamespace())) {
      throw new InvalidRequestException("K8s namespace must be provided for this type of permission.", USER);
    }

    if (!(KUBERNETES.equals(delegateSetupDetails.getDelegateType())
            || HELM_DELEGATE.equals(delegateSetupDetails.getDelegateType()))) {
      throw new InvalidRequestException("Delegate type must be KUBERNETES OR HELM_DELEGATE.");
    }

    validateDelegateToken(accountId, delegateSetupDetails);

    return delegateSetupDetails;
  }

  private void validateDelegateToken(String accountId, DelegateSetupDetails delegateSetupDetails) {
    if (isBlank(delegateSetupDetails.getTokenName())) {
      throw new InvalidRequestException("Delegate Token must be provided.", USER);
    }
    DelegateTokenDetails delegateTokenDetails =
        delegateNgTokenService.getDelegateToken(accountId, delegateSetupDetails.getTokenName());
    if (delegateTokenDetails == null) {
      throw new InvalidRequestException("Provided delegate token does not exist.", USER);
    }
    if (!DelegateTokenStatus.ACTIVE.equals(delegateTokenDetails.getStatus())) {
      throw new InvalidRequestException("Provided delegate token is not valid.", USER);
    }
  }

  private String getCgK8SDelegateTemplate(final String accountId, final boolean isCeEnabled) {
    if (isImmutableDelegate(accountId, KUBERNETES)) {
      return IMMUTABLE_CG_DELEGATE_YAML;
    }

    if (isCeEnabled) {
      return HARNESS_DELEGATE + "-ce.yaml.ftl";
    }
    return HARNESS_DELEGATE + ".yaml.ftl";
  }

  private String obtainK8sTemplateNameFromConfig(final K8sConfigDetails k8sConfigDetails, final String accountId) {
    if (isImmutableDelegate(accountId, KUBERNETES)) {
      return IMMUTABLE_DELEGATE_YAML;
    }

    if (k8sConfigDetails == null || k8sConfigDetails.getK8sPermissionType() == null) {
      return HARNESS_DELEGATE + NG_CLUSTER_ADMIN_YAML;
    }

    switch (k8sConfigDetails.getK8sPermissionType()) {
      case CLUSTER_VIEWER:
        return HARNESS_DELEGATE + NG_CLUSTER_VIEWER_YAML;
      case NAMESPACE_ADMIN:
        return HARNESS_DELEGATE + NG_NAMESPACE_ADMIN_YAML;
      case CLUSTER_ADMIN:
      default:
        return HARNESS_DELEGATE + NG_CLUSTER_ADMIN_YAML;
    }
  }

  @VisibleForTesting
  void validateDelegateProfileId(String accountId, String delegateProfileId) throws InvalidRequestException {
    if (isBlank(delegateProfileId)) {
      return;
    }
    DelegateProfile profile = delegateProfileService.get(accountId, delegateProfileId);
    if (profile == null) {
      throw new InvalidRequestException("Delegate configuration (profile) id does not match any record", USER);
    }
  }

  @Override
  public DelegateStatus getDelegateStatus(String accountId) {
    DelegateConfiguration delegateConfiguration = accountService.getDelegateConfiguration(accountId);

    List<Delegate> delegates = persistence.createQuery(Delegate.class)
                                   .filter(DelegateKeys.accountId, accountId)
                                   .field(DelegateKeys.status)
                                   .notEqual(DelegateInstanceStatus.DELETED)
                                   .asList();

    Map<String, List<DelegateConnectionDetails>> perDelegateConnections =
        delegateConnectionDao.obtainActiveDelegateConnections(accountId);

    return DelegateStatus.builder()
        .publishedVersions(delegateConfiguration.getDelegateVersions())
        .delegates(buildInnerDelegates(accountId, delegates, perDelegateConnections, false))
        .build();
  }

  @Override
  public DelegateStatus getDelegateStatusWithScalingGroups(String accountId) {
    DelegateConfiguration delegateConfiguration = accountService.getDelegateConfiguration(accountId);

    List<Delegate> delegatesWithoutScalingGroup = getDelegatesWithoutScalingGroup(accountId);

    Map<String, List<DelegateConnectionDetails>> activeDelegateConnections =
        delegateConnectionDao.obtainActiveDelegateConnections(accountId);

    List<DelegateScalingGroup> scalingGroups = getDelegateScalingGroups(accountId, activeDelegateConnections);

    return DelegateStatus.builder()
        .publishedVersions(DeployMode.isOnPrem(mainConfiguration.getDeployMode().name())
                ? Lists.newArrayList()
                : delegateVersionListWithoutPatch(delegateConfiguration.getDelegateVersions()))
        .scalingGroups(scalingGroups)
        .delegates(buildInnerDelegates(accountId, delegatesWithoutScalingGroup, activeDelegateConnections, false))
        .build();
  }

  private List<String> delegateVersionListWithoutPatch(List<String> delegateVersions) {
    return delegateVersions.stream().map(version -> substringBefore(version, "-")).collect(toList());
  }

  @NotNull
  private List<DelegateScalingGroup> getDelegateScalingGroups(
      String accountId, Map<String, List<DelegateConnectionDetails>> activeDelegateConnections) {
    List<Delegate> activeDelegates =
        persistence.createQuery(Delegate.class)
            .filter(DelegateKeys.accountId, accountId)
            .field(DelegateKeys.ng)
            .notEqual(true) // notEqual is required to cover all existing delegates that will not have the ng flag set
            .field(DelegateKeys.delegateGroupName)
            .exists()
            .field(DelegateKeys.status)
            .hasAnyOf(Arrays.asList(DelegateInstanceStatus.ENABLED, DelegateInstanceStatus.WAITING_FOR_APPROVAL))
            .asList();

    List<DelegateGroup> delegateGroupList = persistence.createQuery(DelegateGroup.class)
                                                .filter(DelegateGroupKeys.accountId, accountId)
                                                .filter(DelegateGroupKeys.ng, false)
                                                .project(DelegateGroupKeys.upgraderLastUpdated, true)
                                                .project(DelegateGroupKeys.name, true)
                                                .asList();

    Map<String, Long> delegateGroupMap = delegateGroupList.stream().collect(
        Collectors.toMap(DelegateGroup::getName, DelegateGroup::getUpgraderLastUpdated));

    return activeDelegates.stream()
        .collect(groupingBy(Delegate::getDelegateGroupName))
        .entrySet()
        .stream()
        .map(entry -> {
          return DelegateScalingGroup.builder()
              .groupName(entry.getKey())
              .upgraderLastUpdated(delegateGroupMap.getOrDefault(entry.getKey(), 0L))
              .autoUpgrade(setAutoUpgrade(delegateGroupMap.getOrDefault(entry.getKey(), 0L)))
              .delegateGroupExpirationTime(setDelegateScalingGroupExpiration(entry.getValue()))
              .delegates(buildInnerDelegates(accountId, entry.getValue(), activeDelegateConnections, true))
              .build();
        })
        .collect(toList());
  }

  private long setDelegateScalingGroupExpiration(List<Delegate> delegates) {
    return isNotEmpty(delegates)
        ? delegates.stream().max(Comparator.comparing(Delegate::getExpirationTime)).get().getExpirationTime()
        : 0;
  }

  private boolean setAutoUpgrade(Long upgraderLastUpdated) {
    return TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - upgraderLastUpdated) <= 1;
  }

  private List<Delegate> getDelegatesWithoutScalingGroup(String accountId) {
    return persistence.createQuery(Delegate.class)
        .filter(DelegateKeys.accountId, accountId)
        .field(DelegateKeys.ng)
        .notEqual(true) // notEqual is required to cover all existing delegates that will not have the ng flag set
        .field(DelegateKeys.delegateGroupId)
        .doesNotExist()
        .field(DelegateKeys.delegateGroupName)
        .doesNotExist()
        .field(DelegateKeys.status)
        .notEqual(DelegateInstanceStatus.DELETED)
        .asList();
  }

  @NotNull
  private List<DelegateStatus.DelegateInner> buildInnerDelegates(String accountId, List<Delegate> delegates,
      Map<String, List<DelegateConnectionDetails>> perDelegateConnections, boolean filterInactiveDelegates) {
    List<String> delegateTokensNameList = new ArrayList<>();
    delegates.stream()
        .filter(delegate -> !filterInactiveDelegates || perDelegateConnections.containsKey(delegate.getUuid()))
        .forEach(delegate -> delegateTokensNameList.add(delegate.getDelegateTokenName()));
    // TODO: Arpit fetch the tokenStatus from cache instead of db.
    Map<String, Boolean> delegateTokenStatusMap =
        delegateNgTokenService.isDelegateTokenActive(accountId, delegateTokensNameList);

    return delegates.stream()
        .filter(delegate -> !filterInactiveDelegates || perDelegateConnections.containsKey(delegate.getUuid()))
        .map(delegate -> {
          List<DelegateConnectionDetails> connections =
              perDelegateConnections.computeIfAbsent(delegate.getUuid(), uuid -> emptyList());
          DelegateStatus.DelegateInner.DelegateInnerBuilder delegateInnerBuilder =
              DelegateStatus.DelegateInner.builder()
                  .uuid(delegate.getUuid())
                  .delegateName(delegate.getDelegateName())
                  .description(delegate.getDescription())
                  .hostName(delegate.getHostName())
                  .delegateGroupName(delegate.getDelegateGroupName())
                  .ip(delegate.getIp())
                  .status(delegate.getStatus())
                  .lastHeartBeat(delegate.getLastHeartBeat())
                  // currently, we do not return stale connections, but if we do this must filter them out
                  .activelyConnected(!connections.isEmpty())
                  .delegateProfileId(delegate.getDelegateProfileId())
                  .delegateType(delegate.getDelegateType())
                  .polllingModeEnabled(delegate.isPolllingModeEnabled())
                  .proxy(delegate.isProxy())
                  .ceEnabled(delegate.isCeEnabled())
                  .excludeScopes(delegate.getExcludeScopes())
                  .includeScopes(delegate.getIncludeScopes())
                  .tags(delegate.getTags())
                  .profileExecutedAt(delegate.getProfileExecutedAt())
                  .profileError(delegate.isProfileError())
                  .grpcActive(connections.stream().allMatch(connection
                      -> connection.getLastGrpcHeartbeat() > System.currentTimeMillis() - MAX_GRPC_HB_TIMEOUT))
                  .implicitSelectors(delegateSetupService.retrieveDelegateImplicitSelectors(delegate, false))
                  .sampleDelegate(delegate.isSampleDelegate())
                  .connections(connections)
                  .tokenActive(delegate.getDelegateTokenName() == null
                      || (delegateTokenStatusMap.containsKey(delegate.getDelegateTokenName())
                          && delegateTokenStatusMap.get(delegate.getDelegateTokenName())))
                  .delegateExpirationTime(delegate.getExpirationTime());
          // Set autoUpgrade as true for legacy delegate.
          if (!delegate.isImmutable()) {
            delegateInnerBuilder.autoUpgrade(true);
          }
          return delegateInnerBuilder.build();
        })
        .collect(toList());
  }

  @Override
  public Delegate update(final Delegate delegate) {
    final Delegate originalDelegate = delegateCache.get(delegate.getAccountId(), delegate.getUuid(), false);
    final boolean newProfileApplied = originalDelegate != null && !delegate.isNg()
        && compare(originalDelegate.getDelegateProfileId(), delegate.getDelegateProfileId()) != 0;

    final Delegate updatedDelegate;
    final UpdateOperations<Delegate> updateOperations = getDelegateUpdateOperations(delegate);
    if (isGroupedCgDelegate(delegate)) {
      updatedDelegate = updateAllCgDelegatesInGroup(delegate, updateOperations, "ALL");
    } else {
      log.debug("Updating delegate : {}", delegate.getUuid());
      updatedDelegate = updateDelegate(delegate, updateOperations);
    }

    if (newProfileApplied) {
      log.info("New profile applied for delegate {}", delegate.getUuid());
      auditServiceHelper.reportForAuditingUsingAccountId(
          delegate.getAccountId(), originalDelegate, updatedDelegate, Type.UPDATE);
      final DelegateProfile profile =
          delegateProfileService.get(delegate.getAccountId(), delegate.getDelegateProfileId());
      auditServiceHelper.reportForAuditingUsingAccountId(delegate.getAccountId(), null, profile, Type.APPLY);
    }

    return updatedDelegate;
  }

  private Delegate updateEcsDelegateInstance(Delegate delegate) {
    final UpdateOperations<Delegate> updateOperations = getDelegateUpdateOperations(delegate);
    log.info("Updating ECS delegate : {}", delegate.getUuid());
    if (isDelegateWithoutPollingEnabled(delegate)) {
      // This updates delegates, as well as delegateConnection and taksBeingExecuted on delegate
      return updateDelegate(delegate, updateOperations);
    } else {
      // only update lastHeartbeatAt
      return updateHeartbeatForDelegateWithPollingEnabled(delegate);
    }
  }

  private UpdateOperations<Delegate> getDelegateUpdateOperations(final Delegate delegate) {
    final UpdateOperations<Delegate> updateOperations = persistence.createUpdateOperations(Delegate.class);
    setUnset(updateOperations, DelegateKeys.ip, delegate.getIp());
    if (delegate.getStatus() != null) {
      updateOperations.set(DelegateKeys.status, delegate.getStatus());
    }
    setUnset(updateOperations, DelegateKeys.lastHeartBeat, delegate.getLastHeartBeat());
    setUnset(updateOperations, DelegateKeys.validUntil,
        Date.from(OffsetDateTime.now().plusDays(Delegate.TTL.toDays()).toInstant()));
    setUnset(updateOperations, DelegateKeys.version, delegate.getVersion());
    setUnset(updateOperations, DelegateKeys.description, delegate.getDescription());
    if (delegate.getDelegateType() != null) {
      setUnset(updateOperations, DelegateKeys.delegateType, delegate.getDelegateType());
    }
    setUnset(updateOperations, DelegateKeys.delegateProfileId, delegate.getDelegateProfileId());
    setUnset(updateOperations, DelegateKeys.sampleDelegate, delegate.isSampleDelegate());
    setUnset(updateOperations, DelegateKeys.polllingModeEnabled, delegate.isPolllingModeEnabled());
    setUnset(updateOperations, DelegateKeys.proxy, delegate.isProxy());
    setUnset(updateOperations, DelegateKeys.ceEnabled, delegate.isCeEnabled());
    setUnset(updateOperations, DelegateKeys.supportedTaskTypes, delegate.getSupportedTaskTypes());
    if (delegate.getDelegateTokenName() != null) {
      setUnset(updateOperations, DelegateKeys.delegateTokenName, delegate.getDelegateTokenName());
    }
    setUnset(updateOperations, DelegateKeys.heartbeatAsObject, delegate.isHeartbeatAsObject());
    setUnset(updateOperations, DelegateKeys.mtls, delegate.isMtls());

    return updateOperations;
  }

  @Override
  public Delegate updateDescription(String accountId, String delegateId, String newDescription) {
    log.info("Updating delegate description", delegateId);
    persistence.update(persistence.createQuery(Delegate.class)
                           .filter(DelegateKeys.accountId, accountId)
                           .filter(DelegateKeys.uuid, delegateId),
        persistence.createUpdateOperations(Delegate.class).set(DelegateKeys.description, newDescription));

    return delegateCache.get(accountId, delegateId, true);
  }

  @Override
  public Delegate updateApprovalStatus(String accountId, String delegateId, DelegateApproval action)
      throws InvalidRequestException {
    Delegate currentDelegate = persistence.createQuery(Delegate.class)
                                   .filter(DelegateKeys.accountId, accountId)
                                   .filter(DelegateKeys.uuid, delegateId)
                                   .get();
    if (currentDelegate == null) {
      throw new InvalidRequestException("Unable to fetch delegate with delegate ID " + delegateId);
    }
    if (currentDelegate.getStatus() != DelegateInstanceStatus.WAITING_FOR_APPROVAL) {
      throw new InvalidRequestException("Delegate is already in state " + currentDelegate.getStatus().name());
    }

    return updateApprovalStatePerDelegate(currentDelegate, action);
  }

  @Override
  public DelegateApprovalResponse approveDelegatesUsingProfile(
      String accountId, String delegateProfileId, DelegateApproval action) throws InvalidRequestException {
    List<Delegate> currentDelegates = persistence.createQuery(Delegate.class)
                                          .filter(DelegateKeys.accountId, accountId)
                                          .filter(DelegateKeys.delegateProfileId, delegateProfileId)
                                          .asList();

    if (currentDelegates.isEmpty()) {
      throw new InvalidRequestException(format(
          "Unable to fetch any delegate with accountId %s and delegateProfileId %s ", accountId, delegateProfileId));
    }
    List<String> updatedDelegates = new ArrayList<>();
    for (Delegate currentDelegate : currentDelegates) {
      if (DelegateInstanceStatus.WAITING_FOR_APPROVAL.equals(currentDelegate.getStatus())) {
        updatedDelegates.add(currentDelegate.getUuid());
        updateApprovalStatePerDelegate(currentDelegate, action);
      }
    }
    return new DelegateApprovalResponse(
        isNotEmpty(updatedDelegates) ? DELEGATES_UPDATED_RESPONSE : NO_DELEGATES_UPDATED_RESPONSE, updatedDelegates);
  }

  @Override
  public DelegateApprovalResponse approveDelegatesUsingToken(
      String accountId, String delegateTokenName, DelegateApproval action) throws InvalidRequestException {
    List<Delegate> currentDelegates = persistence.createQuery(Delegate.class)
                                          .filter(DelegateKeys.accountId, accountId)
                                          .filter(DelegateKeys.delegateTokenName, delegateTokenName)
                                          .asList();

    if (currentDelegates.isEmpty()) {
      throw new InvalidRequestException(
          format("Unable to fetch any delegate with accountId %s and delegateToken %s ", accountId, delegateTokenName));
    }
    List<String> updatedDelegates = new ArrayList<>();
    for (Delegate currentDelegate : currentDelegates) {
      if (DelegateInstanceStatus.WAITING_FOR_APPROVAL.equals(currentDelegate.getStatus())) {
        updatedDelegates.add(currentDelegate.getUuid());
        updateApprovalStatePerDelegate(currentDelegate, action);
      }
    }
    return new DelegateApprovalResponse(
        isNotEmpty(updatedDelegates) ? DELEGATES_UPDATED_RESPONSE : NO_DELEGATES_UPDATED_RESPONSE, updatedDelegates);
  }

  private Delegate updateApprovalStatePerDelegate(Delegate currentDelegate, DelegateApproval action) {
    DelegateInstanceStatus newDelegateStatus = mapApprovalActionToDelegateStatus(action);
    Type actionEventType = mapActionToEventType(action);

    Query<Delegate> updateQuery = persistence.createQuery(Delegate.class)
                                      .filter(DelegateKeys.uuid, currentDelegate.getUuid())
                                      .filter(DelegateKeys.status, DelegateInstanceStatus.WAITING_FOR_APPROVAL);

    UpdateOperations<Delegate> updateOperations =
        persistence.createUpdateOperations(Delegate.class).set(DelegateKeys.status, newDelegateStatus);

    log.info("Updating approval status from {} to {}", currentDelegate.getStatus(), newDelegateStatus);
    Delegate updatedDelegate = persistence.findAndModify(updateQuery, updateOperations, HPersistence.returnNewOptions);

    auditServiceHelper.reportForAuditingUsingAccountId(
        currentDelegate.getAccountId(), currentDelegate, updatedDelegate, actionEventType);

    if (DelegateInstanceStatus.DELETED == newDelegateStatus) {
      delegateMetricsService.recordDelegateMetrics(currentDelegate, DELEGATE_DESTROYED);
      broadcasterFactory.lookup(STREAM_DELEGATE + currentDelegate.getAccountId(), true)
          .broadcast(SELF_DESTRUCT + currentDelegate.getUuid());
      log.warn("Sent self destruct command to rejected delegate {}.", currentDelegate.getUuid());
    }

    return updatedDelegate;
  }

  private DelegateInstanceStatus mapApprovalActionToDelegateStatus(DelegateApproval action) {
    if (DelegateApproval.ACTIVATE == action) {
      return DelegateInstanceStatus.ENABLED;
    } else {
      return DelegateInstanceStatus.DELETED;
    }
  }

  private Type mapActionToEventType(DelegateApproval action) {
    if (DelegateApproval.ACTIVATE == action) {
      return Type.DELEGATE_APPROVAL;
    } else {
      return Type.DELEGATE_REJECTION;
    }
  }

  @Override
  public Delegate updateHeartbeatForDelegateWithPollingEnabled(Delegate delegate) {
    persistence.update(persistence.createQuery(Delegate.class)
                           .filter(DelegateKeys.accountId, delegate.getAccountId())
                           .filter(DelegateKeys.uuid, delegate.getUuid()),
        persistence.createUpdateOperations(Delegate.class)
            .set(DelegateKeys.lastHeartBeat, currentTimeMillis())
            .set(DelegateKeys.validUntil, Date.from(OffsetDateTime.now().plusDays(Delegate.TTL.toDays()).toInstant())));
    delegateTaskService.touchExecutingTasks(
        delegate.getAccountId(), delegate.getUuid(), delegate.getCurrentlyExecutingDelegateTasks());

    Delegate existingDelegate = delegateCache.get(delegate.getAccountId(), delegate.getUuid(), false);

    if (existingDelegate == null) {
      register(delegate);
      existingDelegate = delegateCache.get(delegate.getAccountId(), delegate.getUuid(), true);
    }

    if (licenseService.isAccountDeleted(existingDelegate.getAccountId())) {
      existingDelegate.setStatus(DelegateInstanceStatus.DELETED);
    }

    existingDelegate.setUseCdn(mainConfiguration.useCdnForDelegateStorage());
    existingDelegate.setUseJreVersion(getTargetJreVersion(delegate.getAccountId()));
    return existingDelegate;
  }

  @Override
  public Delegate updateTags(Delegate delegate) {
    UpdateOperations<Delegate> updateOperations = persistence.createUpdateOperations(Delegate.class);

    // this is important to keep only unique list of tags in db.
    Set<String> uniqueSetOfTags = new HashSet<>(delegate.getTags());

    setUnset(updateOperations, DelegateKeys.tags, new ArrayList<>(uniqueSetOfTags));
    log.info("Updating delegate tags : Delegate:{} tags:{}", delegate.getUuid(), delegate.getTags());

    Delegate updatedDelegate = null;
    if (isGroupedCgDelegate(delegate)) {
      log.info("Updating tags for all delegates in a group {}", delegate.getDelegateGroupName());
      updatedDelegate = updateAllCgDelegatesInGroup(delegate, updateOperations, "TAGS");
    } else {
      updatedDelegate = updateDelegate(delegate, updateOperations);
    }

    auditServiceHelper.reportForAuditingUsingAccountId(
        delegate.getAccountId(), delegate, updatedDelegate, Type.UPDATE_TAG);
    log.info("Auditing updation of Tags for delegate={} in account={}", delegate.getUuid(), delegate.getAccountId());

    return updatedDelegate;
  }

  @Override
  public Delegate updateScopes(Delegate delegate) {
    UpdateOperations<Delegate> updateOperations = persistence.createUpdateOperations(Delegate.class);
    setUnset(updateOperations, DelegateKeys.includeScopes, delegate.getIncludeScopes());
    setUnset(updateOperations, DelegateKeys.excludeScopes, delegate.getExcludeScopes());

    log.info("Updating delegate scopes : Delegate:{} includeScopes:{} excludeScopes:{}", delegate.getUuid(),
        delegate.getIncludeScopes(), delegate.getExcludeScopes());

    auditServiceHelper.reportForAuditingUsingAccountId(delegate.getAccountId(), null, delegate, Type.UPDATE_SCOPE);
    log.info("Auditing update of scope for delegateId={} in accountId={}", delegate.getUuid(), delegate.getAccountId());

    Delegate updatedDelegate = null;
    if (isGroupedCgDelegate(delegate)) {
      log.info("Updating scopes for all delegates in a group {}", delegate.getDelegateGroupName());
      updatedDelegate = updateAllCgDelegatesInGroup(delegate, updateOperations, "SCOPES");
    } else {
      updatedDelegate = updateDelegate(delegate, updateOperations);
    }

    return updatedDelegate;
  }

  private Delegate updateDelegate(Delegate delegate, UpdateOperations<Delegate> updateOperations) {
    Delegate previousDelegate = delegateCache.get(delegate.getAccountId(), delegate.getUuid(), false);

    if (previousDelegate != null && isBlank(delegate.getDelegateProfileId())) {
      updateOperations.unset(DelegateKeys.profileResult)
          .unset(DelegateKeys.profileError)
          .unset(DelegateKeys.profileExecutedAt);

      if (isNotBlank(previousDelegate.getProfileResult())) {
        try {
          fileService.deleteFile(previousDelegate.getProfileResult(), FileBucket.PROFILE_RESULTS);
        } catch (MongoGridFSException e) {
          log.warn("Didn't find profile result file: {}", previousDelegate.getProfileResult());
        }
      }
    }

    persistence.update(persistence.createQuery(Delegate.class)
                           .filter(DelegateKeys.accountId, delegate.getAccountId())
                           .filter(DelegateKeys.uuid, delegate.getUuid()),
        updateOperations);
    delegateTaskService.touchExecutingTasks(
        delegate.getAccountId(), delegate.getUuid(), delegate.getCurrentlyExecutingDelegateTasks());

    eventEmitter.send(Channel.DELEGATES,
        anEvent().withOrgId(delegate.getAccountId()).withUuid(delegate.getUuid()).withType(Type.UPDATE).build());
    return delegateCache.get(delegate.getAccountId(), delegate.getUuid(), true);
  }

  private String processTemplate(Map<String, String> scriptParams, String template) throws IOException {
    try (StringWriter stringWriter = new StringWriter()) {
      templateConfiguration.getTemplate(template).process(scriptParams, stringWriter);
      return stringWriter.toString();
    } catch (TemplateException ex) {
      throw new UnexpectedException("This templates are included in the jar, they should be safe to process", ex);
    }
  }

  @Override
  public DelegateScripts getDelegateScriptsNg(String accountId, String version, String managerHost,
      String verificationHost, String delegateType) throws IOException {
    Optional<String> delegateTokenName = getDelegateTokenNameFromGlobalContext();
    ImmutableMap<String, String> scriptParams = getJarAndScriptRunTimeParamMap(
        TemplateParameters.builder()
            .accountId(accountId)
            .version(version)
            .managerHost(managerHost)
            .verificationHost(verificationHost)
            .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
            .delegateXmx(getDelegateXmx(delegateType))
            .delegateTokenName(delegateTokenName.orElse(null))
            .build(),
        true);
    ImmutableMap<String, String> watcherScriptParams = getJarAndScriptRunTimeParamMap(
        TemplateParameters.builder()
            .accountId(accountId)
            .version(version)
            .managerHost(managerHost)
            .verificationHost(verificationHost)
            .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
            .delegateXmx(getDelegateXmx(delegateType))
            .delegateTokenName(delegateTokenName.orElse(null))
            .watcher(true)
            .build(),
        true);

    DelegateScripts delegateScripts = DelegateScripts.builder().version(version).doUpgrade(false).build();
    if (isNotEmpty(scriptParams)) {
      String upgradeToVersion = scriptParams.get(UPGRADE_VERSION);
      log.info("Upgrading delegate to version: {}", upgradeToVersion);
      boolean doUpgrade;
      if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES) {
        doUpgrade = true;
      } else {
        doUpgrade = !(Version.valueOf(version).equals(Version.valueOf(upgradeToVersion)));
      }
      delegateScripts.setDoUpgrade(doUpgrade);
      delegateScripts.setVersion(upgradeToVersion);
      delegateScripts.setStartScript(processTemplate(watcherScriptParams, "start.sh.ftl"));
      delegateScripts.setDelegateScript(processTemplate(scriptParams, "delegate.sh.ftl"));
      delegateScripts.setStopScript(processTemplate(scriptParams, "stop.sh.ftl"));
      delegateScripts.setSetupProxyScript(processTemplate(scriptParams, "setup-proxy.sh.ftl"));
    }
    return delegateScripts;
  }

  @Override
  public DelegateScripts getDelegateScripts(String accountId, String version, String managerHost,
      String verificationHost, String delegateName) throws IOException {
    Optional<String> delegateTokenName = getDelegateTokenNameFromGlobalContext();
    ImmutableMap<String, String> scriptParams = getJarAndScriptRunTimeParamMap(
        TemplateParameters.builder()
            .accountId(accountId)
            .version(version)
            .managerHost(managerHost)
            .verificationHost(verificationHost)
            .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
            .delegateTokenName(delegateTokenName.orElse(null))
            .delegateName(StringUtils.defaultString(delegateName))
            .build(),
        false);
    ImmutableMap<String, String> watcherScriptParams = getJarAndScriptRunTimeParamMap(
        TemplateParameters.builder()
            .accountId(accountId)
            .version(version)
            .managerHost(managerHost)
            .verificationHost(verificationHost)
            .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
            .delegateTokenName(delegateTokenName.orElse(null))
            .delegateName(StringUtils.defaultString(delegateName))
            .watcher(true)
            .build(),
        false);

    DelegateScripts delegateScripts = DelegateScripts.builder().version(version).doUpgrade(false).build();
    if (isNotEmpty(scriptParams)) {
      String upgradeToVersion = scriptParams.get(UPGRADE_VERSION);
      log.info("Upgrading delegate to version: {}", upgradeToVersion);
      boolean doUpgrade;
      if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES) {
        doUpgrade = true;
      } else {
        doUpgrade = !(Version.valueOf(version).equals(Version.valueOf(upgradeToVersion)));
      }
      delegateScripts.setDoUpgrade(doUpgrade);
      delegateScripts.setVersion(upgradeToVersion);
      delegateScripts.setStartScript(processTemplate(watcherScriptParams, "start.sh.ftl"));
      delegateScripts.setDelegateScript(processTemplate(scriptParams, "delegate.sh.ftl"));
      delegateScripts.setStopScript(processTemplate(scriptParams, "stop.sh.ftl"));
      delegateScripts.setSetupProxyScript(processTemplate(scriptParams, "setup-proxy.sh.ftl"));
    }
    return delegateScripts;
  }

  @Override
  public String getLatestDelegateVersion(String accountId) {
    String delegateMatadata = null;
    try {
      delegateMatadata = delegateVersionCache.get(accountId);
    } catch (ExecutionException e) {
      log.error("Execution exception", e);
    }
    return substringBefore(delegateMatadata, " ").trim();
  }

  private String fetchDelegateMetadataFromStorage() {
    String delegateMetadataUrl = subdomainUrlHelper.getDelegateMetadataUrl(null, null, null);
    try {
      log.info("Fetching delegate metadata from storage: {}", delegateMetadataUrl);
      String result = Http.getResponseStringFromUrl(delegateMetadataUrl, 10, 10).trim();
      log.info("Received from storage: {}", result);
      return result;
    } catch (IOException e) {
      log.warn("Exception in fetching delegate version", e);
    }
    return null;
  }

  private ImmutableMap<String, String> getJarAndScriptRunTimeParamMap(
      final TemplateParameters templateParameters, final boolean isNgDelegate) {
    final CdnConfig cdnConfig = mainConfiguration.getCdnConfig();

    final boolean useCDN = mainConfiguration.useCdnForDelegateStorage() && cdnConfig != null;

    final String delegateMetadataUrl = subdomainUrlHelper.getDelegateMetadataUrl(templateParameters.getAccountId(),
        templateParameters.getManagerHost(), mainConfiguration.getDeployMode().name());
    final String delegateStorageUrl = getDelegateStorageUrl(cdnConfig, useCDN, delegateMetadataUrl);
    final String delegateCheckLocation = delegateMetadataUrl.substring(delegateMetadataUrl.lastIndexOf('/') + 1);

    String latestVersion = null;
    String delegateJarDownloadUrl = null;

    try {
      if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES) {
        log.info("Multi-Version is enabled");
        latestVersion = templateParameters.getVersion();
        final String fullVersion =
            Optional.ofNullable(getDelegateBuildVersion(templateParameters.getVersion())).orElse(null);
        delegateJarDownloadUrl =
            infraDownloadService.getDownloadUrlForDelegate(fullVersion, templateParameters.getAccountId());
      } else {
        final String delegateMatadata = delegateVersionCache.get(templateParameters.getAccountId());
        log.info("Delegate metadata: [{}]", delegateMatadata);
        latestVersion = substringBefore(delegateMatadata, " ").trim();
        final String jarRelativePath = substringAfter(delegateMatadata, " ").trim();
        delegateJarDownloadUrl = delegateStorageUrl + "/" + jarRelativePath;
      }
    } catch (ExecutionException e) {
      log.warn("Unable to fetch delegate version information", e);
      log.warn("CurrentVersion: [{}], LatestVersion=[{}], delegateJarDownloadUrl=[{}]", templateParameters.getVersion(),
          latestVersion, delegateJarDownloadUrl);
    }

    log.info("Current version of delegate :[{}], latest version: [{}] url: [{}]", templateParameters.getVersion(),
        latestVersion, delegateJarDownloadUrl);
    if (doesJarFileExist(delegateJarDownloadUrl)) {
      final String watcherMetadataUrl;
      if (useCDN) {
        watcherMetadataUrl = infraDownloadService.getCdnWatcherMetaDataFileUrl();
      } else {
        watcherMetadataUrl = subdomainUrlHelper.getWatcherMetadataUrl(templateParameters.getAccountId(),
            templateParameters.getManagerHost(), mainConfiguration.getDeployMode().name());
      }
      final String watcherStorageUrl = watcherMetadataUrl.substring(0, watcherMetadataUrl.lastIndexOf('/'));
      final String watcherCheckLocation = watcherMetadataUrl.substring(watcherMetadataUrl.lastIndexOf('/') + 1);

      final String hexkey = format("%040x",
          new BigInteger(1, templateParameters.getAccountId().substring(0, 6).getBytes(StandardCharsets.UTF_8)))
                                .replaceFirst("^0+(?!$)", "");

      final boolean isCiEnabled = isCiEnabled(templateParameters);
      final String accountSecret = getAccountSecret(templateParameters, isNgDelegate);
      final String base64Secret = Base64.getEncoder().encodeToString(accountSecret.getBytes());
      // Ng helm delegates always use immutable image irrespective of FF
      final String delegateDockerImage = (isNgDelegate && HELM_DELEGATE.equals(templateParameters.getDelegateType()))
          ? delegateVersionService.getImmutableDelegateImageTag(templateParameters.getAccountId())
          : delegateVersionService.getDelegateImageTag(
              templateParameters.getAccountId(), templateParameters.getDelegateType());
      ImmutableMap.Builder<String, String> params =
          ImmutableMap.<String, String>builder()
              .put("delegateDockerImage", delegateDockerImage)
              .put("upgraderDockerImage",
                  delegateVersionService.getUpgraderImageTag(
                      templateParameters.getAccountId(), templateParameters.getDelegateType()))
              .put("accountId", templateParameters.getAccountId())
              .put("delegateToken", accountSecret)
              .put("base64Secret", base64Secret)
              .put("hexkey", hexkey)
              .put(UPGRADE_VERSION, latestVersion)
              .put("managerHostAndPort", templateParameters.getManagerHost())
              .put("verificationHostAndPort", templateParameters.getVerificationHost())
              .put("watcherStorageUrl", watcherStorageUrl)
              .put("watcherCheckLocation", watcherCheckLocation)
              .put("delegateStorageUrl", delegateStorageUrl)
              .put("delegateCheckLocation", delegateCheckLocation)
              .put("deployMode", mainConfiguration.getDeployMode().name())
              .put("ciEnabled", String.valueOf(isCiEnabled))
              .put("scmVersion", mainConfiguration.getScmVersion())
              .put("delegateGrpcServicePort", String.valueOf(delegateGrpcConfig.getPort()))
              .put("kubernetesAccountLabel", getAccountIdentifier(templateParameters.getAccountId()))
              .put("dynamicHandlingOfRequestEnabled",
                  String.valueOf(featureFlagService.isEnabled(
                      DELEGATE_ENABLE_DYNAMIC_HANDLING_OF_REQUEST, templateParameters.getAccountId())));

      final boolean isOnPrem = DeployMode.isOnPrem(mainConfiguration.getDeployMode().name());
      params.put("isOnPrem", String.valueOf(isOnPrem));
      if (!isOnPrem) {
        final String watcherVersion =
            substringBefore(delegateVersionService.getWatcherJarVersions(templateParameters.getAccountId()), "-")
                .trim()
                .split("\\.")[2];
        params.put("watcherJarVersion", watcherVersion);
      }

      if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES_ONPREM) {
        params.put("managerTarget", mainConfiguration.getGrpcOnpremDelegateClientConfig().getTarget());
        params.put("managerAuthority", mainConfiguration.getGrpcOnpremDelegateClientConfig().getAuthority());
      }

      if (isNotBlank(templateParameters.getDelegateName())) {
        params.put("delegateName", templateParameters.getDelegateName());
      }

      if (isNotBlank(mainConfiguration.getOcVersion())) {
        params.put("ocVersion", mainConfiguration.getOcVersion());
      }

      if (templateParameters.getDelegateProfile() != null) {
        params.put("delegateProfile", templateParameters.getDelegateProfile());
      }

      if (templateParameters.getDelegateType() != null) {
        params.put("delegateType", templateParameters.getDelegateType());
      }

      if (templateParameters.getLogStreamingServiceBaseUrl() != null) {
        params.put("logStreamingServiceBaseUrl", templateParameters.getLogStreamingServiceBaseUrl());
      }

      params.put("grpcServiceEnabled", String.valueOf(isCiEnabled));
      if (isCiEnabled) {
        params.put("grpcServiceConnectorPort", String.valueOf(delegateGrpcConfig.getPort()));
      } else {
        params.put("grpcServiceConnectorPort", String.valueOf(0));
      }

      params.put("useCdn", String.valueOf(useCDN));
      if (useCDN) {
        params.put("cdnUrl", cdnConfig.getUrl());
        params.put("remoteWatcherUrlCdn", infraDownloadService.getCdnWatcherBaseUrl());
      } else {
        params.put("cdnUrl", EMPTY);
        params.put("remoteWatcherUrlCdn", EMPTY);
      }

      if (isNotBlank(templateParameters.getDelegateXmx())) {
        params.put("delegateXmx", templateParameters.getDelegateXmx());
      } else {
        if (featureFlagService.isEnabled(REDUCE_DELEGATE_MEMORY_SIZE, templateParameters.getAccountId())) {
          params.put("delegateXmx", "-Xmx1536m");
        } else {
          params.put("delegateXmx", "-Xmx4096m");
        }
      }

      JreConfig jreConfig = getJreConfig(templateParameters.getAccountId(), templateParameters.isWatcher());

      Preconditions.checkNotNull(jreConfig, "jreConfig cannot be null");

      params.put(JRE_VERSION_KEY, jreConfig.getVersion());
      params.put(JRE_DIRECTORY, jreConfig.getJreDirectory());
      params.put(JRE_MAC_DIRECTORY, jreConfig.getJreMacDirectory());
      params.put(JRE_TAR_PATH, jreConfig.getJreTarPath());
      params.put("isJdk11Watcher", String.valueOf(isJdk11Watcher(templateParameters.getAccountId())));

      if (jreConfig.getAlpnJarPath() != null) {
        params.put(ALPN_JAR_PATH, jreConfig.getAlpnJarPath());
      }
      params.put("enableCE", String.valueOf(templateParameters.isCeEnabled()));

      if (isNotBlank(templateParameters.getDelegateTags())) {
        params.put("delegateTags", templateParameters.getDelegateTags());
      } else {
        params.put("delegateTags", EMPTY);
      }

      if (isNotBlank(templateParameters.getDelegateDescription())) {
        params.put("delegateDescription", templateParameters.getDelegateDescription());
      } else {
        params.put("delegateDescription", EMPTY);
      }

      if (isNotBlank(templateParameters.getDelegateSize())) {
        params.put("delegateSize", templateParameters.getDelegateSize());
      }

      if (templateParameters.getDelegateReplicas() != 0) {
        params.put("delegateReplicas", String.valueOf(templateParameters.getDelegateReplicas()));
      }

      if (templateParameters.getDelegateRam() != 0) {
        params.put("delegateRam", String.valueOf(templateParameters.getDelegateRam()));
      }

      if (templateParameters.getDelegateCpu() != 0) {
        params.put("delegateCpu", String.valueOf(templateParameters.getDelegateCpu()));
      }

      if (templateParameters.getDelegateRequestsRam() != 0) {
        params.put("delegateRequestsRam", String.valueOf(templateParameters.getDelegateRequestsRam()));
      }

      if (templateParameters.getDelegateRequestsCpu() != 0) {
        params.put("delegateRequestsCpu", String.valueOf(templateParameters.getDelegateRequestsCpu()));
      }

      if (isNotBlank(templateParameters.getDelegateGroupId())) {
        params.put("delegateGroupId", templateParameters.getDelegateGroupId());
      } else {
        params.put("delegateGroupId", "");
      }

      if (isNotBlank(templateParameters.getDelegateGroupName())) {
        params.put("delegateGroupName", templateParameters.getDelegateGroupName());
      } else {
        params.put("delegateGroupName", "");
      }

      params.put("delegateNamespace", getDelegateNamespace(templateParameters.getDelegateNamespace(), isNgDelegate));

      if (templateParameters.getK8sPermissionsType() != null) {
        params.put("k8sPermissionsType", templateParameters.getK8sPermissionsType().name());
      }

      if (isNotBlank(templateParameters.getDelegateTokenName())) {
        params.put("delegateTokenName", templateParameters.getDelegateTokenName());
      }

      params.put("isImmutable",
          String.valueOf(isImmutableDelegate(templateParameters.getAccountId(), templateParameters.getDelegateType())));

      params.put("mtlsEnabled", String.valueOf(templateParameters.isMtlsEnabled()));

      return params.build();
    }
    throw new IllegalStateException("delegate.jar can't be downloaded from " + delegateJarDownloadUrl);
  }

  @VisibleForTesting
  public TemplateParameters finalizeTemplateParametersWithMtlsIfRequired(TemplateParametersBuilder originalBuilder) {
    // build to retrieve current state of builder (as lombok builder doesn't expose getters)
    TemplateParameters original = originalBuilder.build();

    // mTLS is only supported for immutable delegates
    if (!isImmutableDelegate(original.getAccountId(), original.getDelegateType())) {
      return original;
    }

    AgentMtlsEndpointDetails mtlsEndpoint =
        this.agentMtlsEndpointService.getEndpointForAccountOrNull(original.getAccountId());
    if (mtlsEndpoint == null) {
      return original;
    }

    // create a new builder out of the original object to avoid potential idempotency issues
    TemplateParametersBuilder updatedBuilder = original.toBuilder();

    // immutable delegate with mTLS enabled on account - update template parameters accordingly
    updatedBuilder.mtlsEnabled(true);

    /*
     * Update all URIs that are used by the immutable delegate yaml files (manager & log-service)
     * Assumption: managerHost is the base URI of the harness cluster (e.g. "https://app.harness.io")
     */
    String baseUri = original.getManagerHost();

    updatedBuilder.managerHost(
        this.updateUriToTargetMtlsEndpoint(original.getManagerHost(), baseUri, mtlsEndpoint.getFqdn()));
    updatedBuilder.logStreamingServiceBaseUrl(
        this.updateUriToTargetMtlsEndpoint(original.getLogStreamingServiceBaseUrl(), baseUri, mtlsEndpoint.getFqdn()));

    return updatedBuilder.build();
  }

  /**
   * Updates an external Harness URI to point to the mTLS endpoint instead.
   *
   * Assumption:
   *    - Delegate Gateway is always receiving traffic on port 443 (and doesn't require a base path)
   *    - All external Harness URIs are under the same base uri of the cluster
   *      (e.g. "https://app.harness.io", "https://pr.harness.io/del-42")
   *
   * @param originalUri the URI to update
   * @param baseUri the base URI of the harness cluster
   * @param mtlsEndpointFqdn the fqdn of the mTLS endpoint
   * @return the updated URI
   */
  @VisibleForTesting
  public String updateUriToTargetMtlsEndpoint(String originalUri, String baseUri, String mtlsEndpointFqdn) {
    // In case anything is missing - log an error and continue.
    // Worst case it has to be changed in artifacts manually, better than failing the download completely.
    if (isBlank(originalUri) || isBlank(baseUri) || isBlank(mtlsEndpointFqdn)) {
      log.error("Unexpected blank input when updating URI: originalUri '{}', baseUri '{}', mtlsEndpointFqdn '{}'",
          originalUri, baseUri, mtlsEndpointFqdn);
      return originalUri;
    }

    try {
      String originalPath = new URI(originalUri).getPath();
      String basePath = new URI(baseUri).getPath();

      String newPath =
          isNotBlank(basePath) && isNotBlank(originalPath) ? originalPath.replace(basePath, "") : originalPath;

      return new URI("https", mtlsEndpointFqdn, newPath, null, null).toString();
    } catch (Exception ex) {
      log.error("Failed to update URL '{}' with FQDN of mTLS endpoint '{}' using base '{}': {}", originalUri,
          mtlsEndpointFqdn, baseUri, ex);
      throw new UnexpectedException("Failed to update the URL to target the mTLS endpoint.", ex);
    }
  }

  private String getDelegateNamespace(final String delegateNamespace, final boolean isNgDelegate) {
    if (isNotBlank(delegateNamespace)) {
      return delegateNamespace;
    } else {
      return isNgDelegate ? HARNESS_NG_DELEGATE_NAMESPACE : HARNESS_DELEGATE;
    }
  }

  private boolean isCiEnabled(final TemplateParameters inquiry) {
    return inquiry.isCiEnabled() && isNotEmpty(mainConfiguration.getPortal().getJwtNextGenManagerSecret())
        && nonNull(delegateGrpcConfig.getPort());
  }

  private String getDelegateStorageUrl(
      final CdnConfig cdnConfig, final boolean useCDN, final String delegateMetadataUrl) {
    if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES && useCDN) {
      log.info("Using CDN delegateStorageUrl {}", cdnConfig.getUrl());
      return cdnConfig.getUrl();
    }
    return delegateMetadataUrl.substring(0, delegateMetadataUrl.lastIndexOf('/'));
  }

  private boolean doesJarFileExist(final String delegateJarDownloadUrl) {
    if ("local".equals(getEnv()) || DeployMode.isOnPrem(mainConfiguration.getDeployMode().name())) {
      return true;
    } else {
      final int responseCode;
      try (Response response = Http.getUnsafeOkHttpClient(delegateJarDownloadUrl, 10, 10)
                                   .newCall(new Builder().url(delegateJarDownloadUrl).head().build())
                                   .execute()) {
        responseCode = response.code();
      } catch (final IOException e) {
        log.warn("Failed to get jar and script runtime params", e);
        return false;
      }
      log.info("HEAD on downloadUrl got statusCode {}", responseCode);
      return responseCode == 200;
    }
  }

  private String getAccountSecret(final TemplateParameters inquiry, final boolean isNg) {
    final Account account = accountService.get(inquiry.getAccountId());
    if (isNotBlank(inquiry.getDelegateTokenName())) {
      if (isNg) {
        return delegateNgTokenService.getDelegateTokenValue(inquiry.getAccountId(), inquiry.getDelegateTokenName());
      } else {
        return delegateTokenService.getTokenValue(inquiry.getAccountId(), inquiry.getDelegateTokenName());
      }
    } else {
      return account.getAccountKey();
    }
  }

  protected String getEnv() {
    return Optional.ofNullable(sysenv.get(ENV_ENV_VAR)).orElse("local");
  }

  /**
   * Returns JreConfig for a given account Id on the basis of UPGRADE_JRE and USE_CDN_FOR_STORAGE_FILES FeatureFlags.
   *
   * @return
   */
  private JreConfig getJreConfig(final String accountId, final boolean isWatcher) {
    final boolean enabled = !isWatcher || isJdk11Watcher(accountId);
    final String jreVersion = enabled ? mainConfiguration.getMigrateToJre() : mainConfiguration.getCurrentJre();
    JreConfig jreConfig = mainConfiguration.getJreConfigs().get(jreVersion);
    final CdnConfig cdnConfig = mainConfiguration.getCdnConfig();

    if (mainConfiguration.useCdnForDelegateStorage() && cdnConfig != null) {
      final String tarPath = cdnConfig.getCdnJreTarPaths().get(jreVersion);
      final String alpnJarPath = cdnConfig.getAlpnJarPath();
      jreConfig = JreConfig.builder()
                      .version(jreConfig.getVersion())
                      .jreDirectory(jreConfig.getJreDirectory())
                      .jreMacDirectory(jreConfig.getJreMacDirectory())
                      .jreTarPath(tarPath)
                      .alpnJarPath(enabled ? null : alpnJarPath)
                      .build();
    }
    return jreConfig;
  }

  private boolean isJdk11Watcher(final String accountId) {
    if (DeployMode.isOnPrem(mainConfiguration.getDeployMode().name())) {
      return true;
    }
    final String watcherVersion =
        substringBefore(delegateVersionService.getWatcherJarVersions(accountId), "-").trim().split("\\.")[2];
    try {
      final int jdk11_base_watcher_version = 75276;
      if (Integer.parseInt(watcherVersion) < jdk11_base_watcher_version) {
        return false;
      }
      return true;
    } catch (Exception e) {
      log.error("Unable to get watcher version, will start watcher with jdk8 ", e);
      return false;
    }
  }

  /**
   * Returns delegate's JRE version for a given account Id
   *
   * @param accountId
   * @return
   */
  private String getTargetJreVersion(final String accountId) {
    return getJreConfig(accountId, false).getVersion();
  }

  private String getDelegateBuildVersion(String delegateVersion) {
    String delegateVersionNumber = null;
    if (isNotBlank(delegateVersion)) {
      delegateVersionNumber = delegateVersion.substring(delegateVersion.lastIndexOf('.') + 1);
    }
    return delegateVersionNumber;
  }

  @Override
  public File downloadScripts(String managerHost, String verificationUrl, String accountId, String delegateName,
      String delegateProfile, String tokenName) throws IOException {
    checkUniquenessOfDelegateName(accountId, delegateName, false);
    File delegateFile = File.createTempFile(DELEGATE_DIR, ".tar");

    try (TarArchiveOutputStream out = new TarArchiveOutputStream(new FileOutputStream(delegateFile))) {
      out.putArchiveEntry(new TarArchiveEntry(DELEGATE_DIR + "/"));
      out.closeArchiveEntry();

      String version;
      if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES) {
        List<String> delegateVersions = accountService.getDelegateConfiguration(accountId).getDelegateVersions();
        version = delegateVersions.get(delegateVersions.size() - 1);
      } else {
        version = EMPTY_VERSION;
      }

      if (isBlank(delegateProfile) || delegateProfileService.get(accountId, delegateProfile) == null) {
        delegateProfile = delegateProfileService.fetchCgPrimaryProfile(accountId).getUuid();
      }

      ImmutableMap<String, String> scriptParams = getJarAndScriptRunTimeParamMap(
          TemplateParameters.builder()
              .accountId(accountId)
              .version(version)
              .managerHost(managerHost)
              .verificationHost(verificationUrl)
              .delegateName(delegateName)
              .delegateProfile(delegateProfile)
              .delegateType(SHELL_SCRIPT)
              .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
              .delegateTokenName(tokenName)
              .build(),
          false);

      ImmutableMap<String, String> watcherScriptParams = getJarAndScriptRunTimeParamMap(
          TemplateParameters.builder()
              .accountId(accountId)
              .version(version)
              .managerHost(managerHost)
              .verificationHost(verificationUrl)
              .delegateName(delegateName)
              .delegateProfile(delegateProfile)
              .delegateType(SHELL_SCRIPT)
              .watcher(true)
              .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
              .delegateTokenName(tokenName)
              .build(),
          false);

      File start = File.createTempFile("start", ".sh");
      saveProcessedTemplate(watcherScriptParams, start, "start.sh.ftl");
      start = new File(start.getAbsolutePath());
      TarArchiveEntry startTarArchiveEntry = new TarArchiveEntry(start, DELEGATE_DIR + "/start.sh");
      startTarArchiveEntry.setMode(0755);
      out.putArchiveEntry(startTarArchiveEntry);
      try (FileInputStream fis = new FileInputStream(start)) {
        IOUtils.copy(fis, out);
      }
      out.closeArchiveEntry();

      File stop = File.createTempFile("stop", ".sh");
      saveProcessedTemplate(watcherScriptParams, stop, "stop.sh.ftl");
      stop = new File(stop.getAbsolutePath());
      TarArchiveEntry stopTarArchiveEntry = new TarArchiveEntry(stop, DELEGATE_DIR + "/stop.sh");
      stopTarArchiveEntry.setMode(0755);
      out.putArchiveEntry(stopTarArchiveEntry);
      try (FileInputStream fis = new FileInputStream(stop)) {
        IOUtils.copy(fis, out);
      }
      out.closeArchiveEntry();

      File setupProxy = File.createTempFile("setup-proxy", ".sh");
      saveProcessedTemplate(scriptParams, setupProxy, "setup-proxy.sh.ftl");
      setupProxy = new File(setupProxy.getAbsolutePath());
      TarArchiveEntry setupProxyTarArchiveEntry = new TarArchiveEntry(setupProxy, DELEGATE_DIR + "/setup-proxy.sh");
      setupProxyTarArchiveEntry.setMode(0755);
      out.putArchiveEntry(setupProxyTarArchiveEntry);
      try (FileInputStream fis = new FileInputStream(setupProxy)) {
        IOUtils.copy(fis, out);
      }
      out.closeArchiveEntry();

      File initScript = File.createTempFile("init", ".sh");
      saveProcessedTemplate(emptyMap(), initScript, "init.sh.ftl");
      initScript = new File(initScript.getAbsolutePath());
      TarArchiveEntry initScriptTarArchiveEntry = new TarArchiveEntry(initScript, DELEGATE_DIR + "/init.sh");
      initScriptTarArchiveEntry.setMode(0755);
      out.putArchiveEntry(initScriptTarArchiveEntry);
      try (FileInputStream fis = new FileInputStream(initScript)) {
        IOUtils.copy(fis, out);
      }
      out.closeArchiveEntry();

      File readme = File.createTempFile(README, ".txt");
      saveProcessedTemplate(emptyMap(), readme, "readme.txt.ftl");
      readme = new File(readme.getAbsolutePath());
      TarArchiveEntry readmeTarArchiveEntry = new TarArchiveEntry(readme, DELEGATE_DIR + README_TXT);
      out.putArchiveEntry(readmeTarArchiveEntry);
      try (FileInputStream fis = new FileInputStream(readme)) {
        IOUtils.copy(fis, out);
      }
      out.closeArchiveEntry();

      out.flush();
      out.finish();
    }

    File gzipDelegateFile = File.createTempFile(DELEGATE_DIR, TAR_GZ);
    compressGzipFile(delegateFile, gzipDelegateFile);
    delegateTelemetryPublisher.sendTelemetryTrackEvents(accountId, SHELL_SCRIPT, false, DELEGATE_CREATED_EVENT);
    return gzipDelegateFile;
  }

  private void saveProcessedTemplate(Map<String, String> scriptParams, File start, String template) throws IOException {
    try (OutputStreamWriter fileWriter = new OutputStreamWriter(new FileOutputStream(start), UTF_8)) {
      templateConfiguration.getTemplate(template).process(scriptParams, fileWriter);
    } catch (TemplateException ex) {
      throw new UnexpectedException("This templates are included in the jar, they should be safe to process", ex);
    }
  }

  private static void compressGzipFile(File file, File gzipFile) {
    try (FileInputStream fis = new FileInputStream(file); FileOutputStream fos = new FileOutputStream(gzipFile);
         GZIPOutputStream gzipOS = new GZIPOutputStream(fos)) {
      byte[] buffer = new byte[1024];
      int len;
      while ((len = fis.read(buffer)) != -1) {
        gzipOS.write(buffer, 0, len);
      }
    } catch (IOException e) {
      log.error("Error gzipping file.", e);
    }
  }

  @Override
  public File downloadDocker(String managerHost, String verificationUrl, String accountId, String delegateName,
      String delegateProfile, String tokenName) throws IOException {
    checkUniquenessOfDelegateName(accountId, delegateName, false);
    File dockerDelegateFile = File.createTempFile(DOCKER_DELEGATE, ".tar");

    try (TarArchiveOutputStream out = new TarArchiveOutputStream(new FileOutputStream(dockerDelegateFile))) {
      out.putArchiveEntry(new TarArchiveEntry(DOCKER_DELEGATE + "/"));
      out.closeArchiveEntry();

      String version;
      if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES) {
        List<String> delegateVersions = accountService.getDelegateConfiguration(accountId).getDelegateVersions();
        version = delegateVersions.get(delegateVersions.size() - 1);
      } else {
        version = EMPTY_VERSION;
      }

      if (isBlank(delegateProfile) || delegateProfileService.get(accountId, delegateProfile) == null) {
        delegateProfile = delegateProfileService.fetchCgPrimaryProfile(accountId).getUuid();
      }

      ImmutableMap<String, String> scriptParams = getJarAndScriptRunTimeParamMap(
          TemplateParameters.builder()
              .accountId(accountId)
              .version(version)
              .managerHost(managerHost)
              .verificationHost(verificationUrl)
              .delegateName(delegateName)
              .delegateProfile(delegateProfile)
              .delegateType(DOCKER)
              .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
              .delegateTokenName(tokenName)
              .build(),
          false);

      if (isEmpty(scriptParams)) {
        throw new InvalidArgumentsException(Pair.of("scriptParams", "Failed to get jar and script runtime params."));
      }

      String templateName;
      if (isBlank(delegateName)) {
        templateName = "launch-" + HARNESS_DELEGATE + "-without-name.sh.ftl";
      } else {
        templateName = "launch-" + HARNESS_DELEGATE + ".sh.ftl";
      }

      File launch = File.createTempFile("launch-" + HARNESS_DELEGATE, ".sh");
      saveProcessedTemplate(scriptParams, launch, templateName);
      launch = new File(launch.getAbsolutePath());
      TarArchiveEntry launchTarArchiveEntry =
          new TarArchiveEntry(launch, DOCKER_DELEGATE + "/launch-" + HARNESS_DELEGATE + ".sh");
      launchTarArchiveEntry.setMode(0755);
      out.putArchiveEntry(launchTarArchiveEntry);
      try (FileInputStream fis = new FileInputStream(launch)) {
        IOUtils.copy(fis, out);
      }
      out.closeArchiveEntry();

      File readme = File.createTempFile(README, ".txt");
      saveProcessedTemplate(emptyMap(), readme, "readme-docker.txt.ftl");
      readme = new File(readme.getAbsolutePath());
      TarArchiveEntry readmeTarArchiveEntry = new TarArchiveEntry(readme, DOCKER_DELEGATE + README_TXT);

      out.putArchiveEntry(readmeTarArchiveEntry);
      try (FileInputStream fis = new FileInputStream(readme)) {
        IOUtils.copy(fis, out);
      }
      out.closeArchiveEntry();

      out.flush();
      out.finish();
    }

    File gzipDockerDelegateFile = File.createTempFile(DELEGATE_DIR, TAR_GZ);
    compressGzipFile(dockerDelegateFile, gzipDockerDelegateFile);
    delegateTelemetryPublisher.sendTelemetryTrackEvents(accountId, DOCKER, false, DELEGATE_CREATED_EVENT);
    return gzipDockerDelegateFile;
  }

  @Override
  public File downloadKubernetes(String managerHost, String verificationUrl, String accountId, String delegateName,
      String delegateProfile, String tokenName) throws IOException {
    checkUniquenessOfDelegateName(accountId, delegateName, false);
    File kubernetesDelegateFile = File.createTempFile(KUBERNETES_DELEGATE, ".tar");

    try (TarArchiveOutputStream out = new TarArchiveOutputStream(new FileOutputStream(kubernetesDelegateFile))) {
      out.putArchiveEntry(new TarArchiveEntry(KUBERNETES_DELEGATE + "/"));
      out.closeArchiveEntry();

      String version;
      if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES) {
        List<String> delegateVersions = accountService.getDelegateConfiguration(accountId).getDelegateVersions();
        version = delegateVersions.get(delegateVersions.size() - 1);
      } else {
        version = EMPTY_VERSION;
      }
      boolean isCiEnabled = accountService.isNextGenEnabled(accountId);

      int delegateRam = featureFlagService.isEnabled(REDUCE_DELEGATE_MEMORY_SIZE, accountId) ? 4 : 8;

      ImmutableMap<String, String> scriptParams = getJarAndScriptRunTimeParamMap(
          this.finalizeTemplateParametersWithMtlsIfRequired(
              TemplateParameters.builder()
                  .accountId(accountId)
                  .version(version)
                  .managerHost(managerHost)
                  .verificationHost(verificationUrl)
                  .delegateName(delegateName)
                  .delegateGroupName(delegateName)
                  .delegateNamespace(HARNESS_DELEGATE)
                  .delegateProfile(delegateProfile == null ? "" : delegateProfile)
                  .delegateType(KUBERNETES)
                  .ciEnabled(isCiEnabled)
                  .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
                  .delegateTokenName(tokenName)
                  .delegateCpu(1)
                  .delegateRam(delegateRam)),
          false);

      File yaml = File.createTempFile(HARNESS_DELEGATE, YAML);
      saveProcessedTemplate(scriptParams, yaml, getCgK8SDelegateTemplate(accountId, false));
      yaml = new File(yaml.getAbsolutePath());
      TarArchiveEntry yamlTarArchiveEntry =
          new TarArchiveEntry(yaml, KUBERNETES_DELEGATE + "/" + HARNESS_DELEGATE + YAML);
      out.putArchiveEntry(yamlTarArchiveEntry);
      try (FileInputStream fis = new FileInputStream(yaml)) {
        IOUtils.copy(fis, out);
      }
      out.closeArchiveEntry();

      addReadmeFile(out);

      out.flush();
      out.finish();
    }

    File gzipKubernetesDelegateFile = File.createTempFile(DELEGATE_DIR, TAR_GZ);
    compressGzipFile(kubernetesDelegateFile, gzipKubernetesDelegateFile);
    delegateTelemetryPublisher.sendTelemetryTrackEvents(accountId, KUBERNETES, false, DELEGATE_CREATED_EVENT);
    return gzipKubernetesDelegateFile;
  }

  @Override
  public File downloadCeKubernetesYaml(String managerHost, String verificationUrl, String accountId,
      String delegateName, String delegateProfile, String tokenName) throws IOException {
    checkUniquenessOfDelegateName(accountId, delegateName, false);
    String version;
    if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES) {
      List<String> delegateVersions = accountService.getDelegateConfiguration(accountId).getDelegateVersions();
      version = delegateVersions.get(delegateVersions.size() - 1);
    } else {
      version = EMPTY_VERSION;
    }
    ImmutableMap<String, String> scriptParams = getJarAndScriptRunTimeParamMap(
        this.finalizeTemplateParametersWithMtlsIfRequired(
            TemplateParameters.builder()
                .accountId(accountId)
                .version(version)
                .managerHost(managerHost)
                .verificationHost(verificationUrl)
                .delegateName(delegateName)
                .delegateNamespace(HARNESS_DELEGATE)
                .delegateProfile(delegateProfile == null ? "" : delegateProfile)
                .delegateType(CE_KUBERNETES)
                .ceEnabled(true)
                .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
                .delegateTokenName(tokenName)
                .ciEnabled(false)
                .delegateCpu(1)
                .delegateRam(4)),
        false);

    File yaml = File.createTempFile(HARNESS_DELEGATE, YAML);
    saveProcessedTemplate(scriptParams, yaml, getCgK8SDelegateTemplate(accountId, true));
    delegateTelemetryPublisher.sendTelemetryTrackEvents(accountId, CE_KUBERNETES, false, DELEGATE_CREATED_EVENT);
    return new File(yaml.getAbsolutePath());
  }

  private void addReadmeFile(TarArchiveOutputStream out) throws IOException {
    File readme = File.createTempFile(README, ".txt");
    saveProcessedTemplate(emptyMap(), readme, "readme-kubernetes.txt.ftl");
    readme = new File(readme.getAbsolutePath());
    TarArchiveEntry readmeTarArchiveEntry = new TarArchiveEntry(readme, KUBERNETES_DELEGATE + README_TXT);
    out.putArchiveEntry(readmeTarArchiveEntry);
    try (FileInputStream fis = new FileInputStream(readme)) {
      IOUtils.copy(fis, out);
    }
    out.closeArchiveEntry();
  }

  @Override
  public File downloadDelegateValuesYamlFile(String managerHost, String verificationUrl, String accountId,
      String delegateName, String delegateProfile, String tokenName) throws IOException {
    String version;
    checkUniquenessOfDelegateName(accountId, delegateName, false);
    if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES) {
      List<String> delegateVersions = accountService.getDelegateConfiguration(accountId).getDelegateVersions();
      version = delegateVersions.get(delegateVersions.size() - 1);
    } else {
      version = EMPTY_VERSION;
    }

    ImmutableMap<String, String> params = getJarAndScriptRunTimeParamMap(
        TemplateParameters.builder()
            .accountId(accountId)
            .version(version)
            .managerHost(managerHost)
            .verificationHost(verificationUrl)
            .delegateName(delegateName)
            .delegateProfile(delegateProfile == null ? "" : delegateProfile)
            .delegateType(HELM_DELEGATE)
            .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
            .delegateTokenName(tokenName)
            .build(),
        false);

    File yaml = File.createTempFile(HARNESS_DELEGATE_VALUES_YAML, YAML);
    saveProcessedTemplate(params, yaml, "delegate-helm-values.yaml.ftl");
    delegateTelemetryPublisher.sendTelemetryTrackEvents(accountId, HELM_DELEGATE, false, DELEGATE_CREATED_EVENT);
    return yaml;
  }

  @Override
  public File downloadECSDelegate(String managerHost, String verificationUrl, String accountId, boolean awsVpcMode,
      String hostname, String delegateGroupName, String delegateProfile, String tokenName) throws IOException {
    checkUniquenessOfDelegateName(accountId, delegateGroupName, false);
    File ecsDelegateFile = File.createTempFile(ECS_DELEGATE, ".tar");

    try (TarArchiveOutputStream out = new TarArchiveOutputStream(new FileOutputStream(ecsDelegateFile))) {
      out.putArchiveEntry(new TarArchiveEntry(ECS_DELEGATE + "/"));
      out.closeArchiveEntry();

      String version;
      if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES) {
        List<String> delegateVersions = accountService.getDelegateConfiguration(accountId).getDelegateVersions();
        version = delegateVersions.get(delegateVersions.size() - 1);
      } else {
        version = EMPTY_VERSION;
      }

      DelegateGroup delegateGroup = upsertDelegateGroup(delegateGroupName, accountId, null);

      ImmutableMap<String, String> scriptParams = getJarAndScriptRunTimeParamMap(
          TemplateParameters.builder()
              .accountId(accountId)
              .version(version)
              .managerHost(managerHost)
              .verificationHost(verificationUrl)
              .delegateName(StringUtils.EMPTY)
              .delegateProfile(delegateProfile == null ? "" : delegateProfile)
              .delegateType(ECS)
              .delegateGroupId(delegateGroup.getUuid())
              .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
              .delegateTokenName(tokenName)
              .delegateGroupName(delegateGroupName)
              .build(),
          false);

      scriptParams = updateMapForEcsDelegate(awsVpcMode, hostname, scriptParams);

      // Add Task Spec Json file
      File yaml = File.createTempFile("ecs-spec", ".json");
      saveProcessedTemplate(scriptParams, yaml, "harness-ecs-delegate.json.ftl");
      yaml = new File(yaml.getAbsolutePath());
      TarArchiveEntry yamlTarArchiveEntry = new TarArchiveEntry(yaml, ECS_DELEGATE + "/ecs-task-spec.json");
      out.putArchiveEntry(yamlTarArchiveEntry);
      try (FileInputStream fis = new FileInputStream(yaml)) {
        IOUtils.copy(fis, out);
      }
      out.closeArchiveEntry();

      // Add Task "Service Spec Json for awsvpc mode" file
      File serviceJson = File.createTempFile("ecs-service-spec", ".json");
      saveProcessedTemplate(scriptParams, serviceJson, "harness-ecs-delegate-service.json.ftl");
      serviceJson = new File(serviceJson.getAbsolutePath());
      TarArchiveEntry serviceJsonTarArchiveEntry =
          new TarArchiveEntry(serviceJson, ECS_DELEGATE + "/service-spec-for-awsvpc-mode.json");
      out.putArchiveEntry(serviceJsonTarArchiveEntry);
      try (FileInputStream fis = new FileInputStream(serviceJson)) {
        IOUtils.copy(fis, out);
      }
      out.closeArchiveEntry();

      // Add Readme file
      File readme = File.createTempFile(README, ".txt");
      saveProcessedTemplate(emptyMap(), readme, "readme-ecs.txt.ftl");
      readme = new File(readme.getAbsolutePath());
      TarArchiveEntry readmeTarArchiveEntry = new TarArchiveEntry(readme, ECS_DELEGATE + README_TXT);
      out.putArchiveEntry(readmeTarArchiveEntry);
      try (FileInputStream fis = new FileInputStream(readme)) {
        IOUtils.copy(fis, out);
      }

      out.closeArchiveEntry();

      out.flush();
      out.finish();
    }

    File gzipEcsDelegateFile = File.createTempFile(DELEGATE_DIR, TAR_GZ);
    compressGzipFile(ecsDelegateFile, gzipEcsDelegateFile);
    delegateTelemetryPublisher.sendTelemetryTrackEvents(accountId, ECS, false, DELEGATE_CREATED_EVENT);
    return gzipEcsDelegateFile;
  }

  private ImmutableMap<String, String> updateMapForEcsDelegate(
      final boolean awsVpcMode, String hostname, final Map<String, String> scriptParams) {
    final Map<String, String> map = new HashMap<>(scriptParams);
    // AWSVPC mode, hostname must be null
    if (awsVpcMode) {
      map.put("networkModeForTask", "\"networkMode\": \"awsvpc\",");
      map.put("hostnameForDelegate", StringUtils.EMPTY);
    } else {
      map.put("networkModeForTask", StringUtils.EMPTY);
      if (isBlank(hostname)) {
        // hostname not provided, use as null, so dockerId will become hostname in ecs
        hostname = HARNESS_ECS_DELEGATE;
      }
      map.put("hostnameForDelegate", "\"hostname\": \"" + hostname + "\",");
    }

    return ImmutableMap.copyOf(map);
  }

  @Override
  public Delegate add(Delegate delegate) {
    Delegate savedDelegate;
    String accountId = delegate.getAccountId();

    DelegateProfile delegateProfile = delegateProfileService.get(accountId, delegate.getDelegateProfileId());
    if (delegateProfile == null && !delegate.isNg()) {
      delegateProfile = delegateProfileService.fetchCgPrimaryProfile(accountId);
      delegate.setDelegateProfileId(delegateProfile.getUuid());
    }

    if (delegateProfile != null && delegateProfile.isApprovalRequired()) {
      delegate.setStatus(DelegateInstanceStatus.WAITING_FOR_APPROVAL);
    } else {
      delegate.setStatus(DelegateInstanceStatus.ENABLED);
    }

    int maxUsageAllowed = delegatesFeature.getMaxUsageAllowedForAccount(accountId);
    if (maxUsageAllowed != Integer.MAX_VALUE) {
      try (AcquiredLock ignored =
               persistentLocker.acquireLock("delegateCountLock-" + accountId, Duration.ofMinutes(3))) {
        long currentDelegateCount = getTotalNumberOfDelegates(accountId);
        if (currentDelegateCount < maxUsageAllowed) {
          savedDelegate = saveDelegate(delegate);
        } else {
          throw new LimitsExceededException(
              format("Can not add delegate to the account. Maximum [%d] delegates are supported", maxUsageAllowed),
              USAGE_LIMITS_EXCEEDED, USER);
        }
      }
    } else {
      savedDelegate = saveDelegate(delegate);
    }

    log.info("Delegate saved: {}", savedDelegate);

    // When polling is enabled for delegate, do not perform these event publishing
    if (isDelegateWithoutPollingEnabled(delegate)) {
      eventEmitter.send(Channel.DELEGATES,
          anEvent().withOrgId(delegate.getAccountId()).withUuid(delegate.getUuid()).withType(Type.CREATE).build());
    }

    updateWithTokenAndSeqNumIfEcsDelegate(delegate, savedDelegate);
    eventPublishHelper.publishInstalledDelegateEvent(delegate.getAccountId(), delegate.getUuid());

    try {
      if (savedDelegate.isCeEnabled()) {
        subject.fireInform(DelegateObserver::onAdded, savedDelegate);
        remoteObserverInformer.sendEvent(ReflectionUtils.getMethod(DelegateObserver.class, "onAdded", Delegate.class),
            DelegateServiceImpl.class, savedDelegate);
      }
    } catch (Exception e) {
      log.error("Encountered exception while informing the observers of Delegate.", e);
    }
    return savedDelegate;
  }

  private long getTotalNumberOfDelegates(String accountId) {
    return persistence.createQuery(Delegate.class)
        .filter(DelegateKeys.accountId, accountId)
        .field(DelegateKeys.status)
        .notEqual(DelegateInstanceStatus.DELETED)
        .count();
  }

  private Delegate saveDelegate(Delegate delegate) {
    log.info("Adding delegate {} for account {}", delegate.getHostName(), delegate.getAccountId());
    persistence.save(delegate);
    log.info("Delegate saved: {}", delegate);
    return delegate;
  }

  @Override
  public void delete(String accountId, String delegateId) {
    Delegate existingDelegate = persistence.createQuery(Delegate.class)
                                    .filter(DelegateKeys.accountId, accountId)
                                    .filter(DelegateKeys.uuid, delegateId)
                                    .project(DelegateKeys.ip, true)
                                    .project(DelegateKeys.ng, true)
                                    .project(DelegateKeys.hostName, true)
                                    .project(DelegateKeys.owner, true)
                                    .project(DelegateKeys.delegateGroupName, true)
                                    .get();

    if (existingDelegate != null) {
      if (delegateConnectionDao.checkAnyDelegateIsConnected(accountId, Arrays.asList(delegateId))) {
        throw new InvalidRequestException(format("Unable to delete delegate. Delegate %s is connected", delegateId));
      }
      // before deleting delegate, check if any alert is open for delegate, if yes, close it.
      if (isEmpty(existingDelegate.getDelegateGroupName())) {
        alertService.closeAlert(accountId, GLOBAL_APP_ID, AlertType.DelegatesDown,
            DelegatesDownAlert.builder()
                .accountId(accountId)
                .obfuscatedIpAddress(obfuscate(existingDelegate.getIp()))
                .hostName(existingDelegate.getHostName())
                .build());
      } else {
        alertService.closeAlert(accountId, GLOBAL_APP_ID, AlertType.DelegatesDown,
            DelegatesDownAlert.builder()
                .accountId(accountId)
                .delegateGroupName(existingDelegate.getDelegateGroupName())
                .build());
      }

    } else {
      throw new InvalidRequestException("Unable to fetch delegate with delegate id " + delegateId);
    }

    persistence.delete(persistence.createQuery(Delegate.class)
                           .filter(DelegateKeys.accountId, accountId)
                           .filter(DelegateKeys.uuid, delegateId));
    sendDelegateDeleteAuditEvent(existingDelegate, accountId);
    log.info("Delegate: {} deleted.", delegateId);
  }

  @Override
  public void retainOnlySelectedDelegatesAndDeleteRest(String accountId, List<String> delegatesToRetain) {
    if (EmptyPredicate.isNotEmpty(delegatesToRetain)) {
      if (delegateConnectionDao.checkAnyDelegateIsConnected(accountId, delegatesToRetain)) {
        throw new InvalidRequestException(
            format("Unable to delete delegate[s]. Anyone delegate %s is connected", delegatesToRetain));
      }
      persistence.delete(persistence.createQuery(Delegate.class)
                             .filter(DelegateKeys.accountId, accountId)
                             .field(DelegateKeys.uuid)
                             .notIn(delegatesToRetain));
    } else {
      log.info("List of delegates to retain is empty. In order to delete delegates, pass a list of delegate IDs");
    }
  }

  @Override
  public void deleteDelegateGroup(String accountId, String delegateGroupId) {
    log.info("Deleting delegate group: {} and all belonging delegates.", delegateGroupId);
    List<Delegate> groupDelegates = persistence.createQuery(Delegate.class)
                                        .filter(DelegateKeys.accountId, accountId)
                                        .filter(DelegateKeys.delegateGroupId, delegateGroupId)
                                        .project(DelegateKeys.owner, true)
                                        .asList();

    for (Delegate delegate : groupDelegates) {
      try {
        delete(accountId, delegate.getUuid());
      } catch (InvalidRequestException exception) {
        log.error("Unable to delete delegate ", exception);
      }
    }
    DelegateGroup delegateGroup = persistence.createQuery(DelegateGroup.class)
                                      .filter(DelegateGroupKeys.accountId, accountId)
                                      .filter(DelegateGroupKeys.uuid, delegateGroupId)
                                      .get();
    if (delegateGroup == null) {
      return;
    }

    persistence.delete(persistence.createQuery(DelegateGroup.class)
                           .filter(DelegateGroupKeys.accountId, accountId)
                           .filter(DelegateGroupKeys.uuid, delegateGroupId));
    log.info("Delegate group: {} and all belonging delegates have been deleted.", delegateGroupId);

    String orgIdentifier = delegateGroup.getOwner() != null
        ? DelegateEntityOwnerHelper.extractOrgIdFromOwnerIdentifier(delegateGroup.getOwner().getIdentifier())
        : null;

    String projectIdentifier = delegateGroup.getOwner() != null
        ? DelegateEntityOwnerHelper.extractProjectIdFromOwnerIdentifier(delegateGroup.getOwner().getIdentifier())
        : null;

    outboxService.save(
        DelegateGroupDeleteEvent.builder()
            .accountIdentifier(accountId)
            .orgIdentifier(orgIdentifier)
            .projectIdentifier(projectIdentifier)
            .delegateGroupId(delegateGroupId)
            .delegateSetupDetails(DelegateSetupDetails.builder()
                                      .delegateConfigurationId(delegateGroup.getDelegateConfigurationId())
                                      .description(delegateGroup.getDescription())
                                      .k8sConfigDetails(delegateGroup.getK8sConfigDetails())
                                      .name(delegateGroup.getName())
                                      .size(delegateGroup.getSizeDetails().getSize())
                                      .orgIdentifier(orgIdentifier)
                                      .delegateType(delegateGroup.getDelegateType())
                                      .projectIdentifier(projectIdentifier)
                                      .build())
            .build());

    DelegateEntityOwner owner = isNotEmpty(groupDelegates) ? groupDelegates.get(0).getOwner() : null;
    publishDelegateChangeEventViaEventFramework(accountId, delegateGroupId, owner, DELETE_ACTION);
  }

  @Override
  public void deleteDelegateGroupV2(String accountId, String orgId, String projectId, String identifier) {
    log.info("Deleting delegate group: {} and all belonging delegates.", identifier);
    DelegateGroup delegateGroup =
        persistence.createQuery(DelegateGroup.class)
            .filter(DelegateGroupKeys.accountId, accountId)
            .filter(DelegateGroupKeys.owner, DelegateEntityOwnerHelper.buildOwner(orgId, projectId))
            .filter(DelegateGroupKeys.identifier, identifier)
            .get();

    if (delegateGroup == null) {
      log.info("Delegate group doesn't exist or it is already deleted.");
      return;
    }

    String delegateGroupUuid = delegateGroup.getUuid();
    List<Delegate> groupDelegates = persistence.createQuery(Delegate.class)
                                        .filter(DelegateKeys.accountId, accountId)
                                        .filter(DelegateKeys.delegateGroupId, delegateGroupUuid)
                                        .project(DelegateKeys.owner, true)
                                        .asList();

    for (Delegate delegate : groupDelegates) {
      delete(accountId, delegate.getUuid());
    }

    persistence.delete(persistence.createQuery(DelegateGroup.class)
                           .filter(DelegateGroupKeys.accountId, accountId)
                           .filter(DelegateGroupKeys.uuid, delegateGroupUuid));
    log.info("Delegate group: {} and all belonging delegates have been deleted.", delegateGroupUuid);

    outboxService.save(
        DelegateGroupDeleteEvent.builder()
            .accountIdentifier(accountId)
            .orgIdentifier(orgId)
            .projectIdentifier(projectId)
            .delegateGroupId(delegateGroupUuid)
            .delegateSetupDetails(
                DelegateSetupDetails.builder()
                    .delegateConfigurationId(delegateGroup.getDelegateConfigurationId())
                    .description(delegateGroup.getDescription())
                    .k8sConfigDetails(delegateGroup.getK8sConfigDetails())
                    .name(delegateGroup.getName())
                    .size(delegateGroup.getSizeDetails() != null ? delegateGroup.getSizeDetails().getSize() : null)
                    .orgIdentifier(orgId)
                    .projectIdentifier(projectId)
                    .delegateType(delegateGroup.getDelegateType())
                    .build())
            .build());

    DelegateEntityOwner owner = isNotEmpty(groupDelegates) ? groupDelegates.get(0).getOwner() : null;
    publishDelegateChangeEventViaEventFramework(accountId, delegateGroupUuid, owner, DELETE_ACTION);
  }

  private void publishDelegateChangeEventViaEventFramework(
      String accountId, String delegateGroupId, DelegateEntityOwner owner, String action) {
    try {
      EntityChangeDTO.Builder entityChangeDTOBuilder = EntityChangeDTO.newBuilder()
                                                           .setAccountIdentifier(StringValue.of(accountId))
                                                           .setIdentifier(StringValue.of(delegateGroupId));

      if (owner != null) {
        String orgIdentifier = DelegateEntityOwnerHelper.extractOrgIdFromOwnerIdentifier(owner.getIdentifier());
        if (isNotBlank(orgIdentifier)) {
          entityChangeDTOBuilder.setOrgIdentifier(StringValue.of(orgIdentifier));
        }

        String projectIdentifier = DelegateEntityOwnerHelper.extractProjectIdFromOwnerIdentifier(owner.getIdentifier());
        if (isNotBlank(projectIdentifier)) {
          entityChangeDTOBuilder.setProjectIdentifier(StringValue.of(projectIdentifier));
        }
      }

      eventProducer.send(
          Message.newBuilder()
              .putAllMetadata(ImmutableMap.of("accountId", accountId, EventsFrameworkMetadataConstants.ENTITY_TYPE,
                  EventsFrameworkMetadataConstants.DELEGATE_ENTITY, EventsFrameworkMetadataConstants.ACTION, action))
              .setData(entityChangeDTOBuilder.build().toByteString())
              .build());
    } catch (Exception ex) {
      log.error(String.format(
          "Failed to publish delegate group %s event for accountId %s via event framework.", action, accountId));
    }
  }

  @Override
  public DelegateRegisterResponse register(Delegate delegate) {
    if (licenseService.isAccountDeleted(delegate.getAccountId())) {
      delegateMetricsService.recordDelegateMetrics(delegate, DELEGATE_DESTROYED);
      broadcasterFactory.lookup(STREAM_DELEGATE + delegate.getAccountId(), true).broadcast(SELF_DESTRUCT);
      log.warn("Sending self destruct command from register delegate because the account is deleted.");
      delegateMetricsService.recordDelegateMetrics(delegate, DELEGATE_REGISTRATION_FAILED);
      return DelegateRegisterResponse.builder().action(DelegateRegisterResponse.Action.SELF_DESTRUCT).build();
    }

    if (isNotBlank(delegate.getDelegateGroupId())) {
      DelegateGroup delegateGroup = persistence.get(DelegateGroup.class, delegate.getDelegateGroupId());
      if (delegateGroup == null || DelegateGroupStatus.DELETED == delegateGroup.getStatus()) {
        log.warn("Sending self destruct command from register delegate because the delegate group is deleted.");
        delegateMetricsService.recordDelegateMetrics(delegate, DELEGATE_REGISTRATION_FAILED);
        return DelegateRegisterResponse.builder().action(DelegateRegisterResponse.Action.SELF_DESTRUCT).build();
      }
    }

    if (accountService.isAccountMigrated(delegate.getAccountId())) {
      String migrateMsg = MIGRATE + accountService.get(delegate.getAccountId()).getMigratedToClusterUrl();
      broadcasterFactory.lookup(STREAM_DELEGATE + delegate.getAccountId(), true).broadcast(migrateMsg);
      return DelegateRegisterResponse.builder()
          .action(DelegateRegisterResponse.Action.MIGRATE)
          .migrateUrl(accountService.get(delegate.getAccountId()).getMigratedToClusterUrl())
          .build();
    }

    final Delegate existingDelegate = getExistingDelegate(
        delegate.getAccountId(), delegate.getHostName(), delegate.isNg(), delegate.getDelegateType(), delegate.getIp());

    if (existingDelegate != null && existingDelegate.getStatus() == DelegateInstanceStatus.DELETED) {
      broadcasterFactory.lookup(STREAM_DELEGATE + delegate.getAccountId(), true)
          .broadcast(SELF_DESTRUCT + existingDelegate.getUuid());
      log.warn(
          "Sending self destruct command from register delegate because the existing delegate has status deleted.");
      delegateMetricsService.recordDelegateMetrics(delegate, DELEGATE_REGISTRATION_FAILED);
      return DelegateRegisterResponse.builder().action(DelegateRegisterResponse.Action.SELF_DESTRUCT).build();
    }

    if (existingDelegate != null) {
      log.debug("Delegate {} already registered for Hostname with : {} IP: {}", delegate.getUuid(),
          delegate.getHostName(), delegate.getIp());
    } else {
      log.info("Registering delegate for Hostname: {} IP: {}", delegate.getHostName(), delegate.getIp());
    }

    if (ECS.equals(delegate.getDelegateType())) {
      return registerResponseFromDelegate(handleEcsDelegateRequest(delegate));
    } else {
      return registerResponseFromDelegate(upsertDelegateOperation(existingDelegate, delegate));
    }
  }

  @Override
  public DelegateRegisterResponse register(final DelegateParams delegateParams, final boolean isConnectedUsingMtls) {
    if (licenseService.isAccountDeleted(delegateParams.getAccountId())) {
      delegateMetricsService.recordDelegateMetrics(
          Delegate.builder().accountId(delegateParams.getAccountId()).version(delegateParams.getVersion()).build(),
          DELEGATE_DESTROYED);
      broadcasterFactory.lookup(STREAM_DELEGATE + delegateParams.getAccountId(), true).broadcast(SELF_DESTRUCT);
      log.warn("Sending self destruct command from register delegate parameters because the account is deleted.");
      return DelegateRegisterResponse.builder().action(DelegateRegisterResponse.Action.SELF_DESTRUCT).build();
    }

    if (isNotBlank(delegateParams.getDelegateGroupId())) {
      final DelegateGroup delegateGroup = persistence.get(DelegateGroup.class, delegateParams.getDelegateGroupId());

      if (delegateGroup == null || DelegateGroupStatus.DELETED == delegateGroup.getStatus()) {
        log.warn(
            "Sending self destruct command from register delegate parameters because the delegate group is deleted.");
        return DelegateRegisterResponse.builder().action(DelegateRegisterResponse.Action.SELF_DESTRUCT).build();
      }
    }

    if (accountService.isAccountMigrated(delegateParams.getAccountId())) {
      String migrateMsg = MIGRATE + accountService.get(delegateParams.getAccountId()).getMigratedToClusterUrl();
      broadcasterFactory.lookup(STREAM_DELEGATE + delegateParams.getAccountId(), true).broadcast(migrateMsg);
      return DelegateRegisterResponse.builder()
          .action(DelegateRegisterResponse.Action.MIGRATE)
          .migrateUrl(accountService.get(delegateParams.getAccountId()).getMigratedToClusterUrl())
          .build();
    }

    final Delegate existingDelegate = getExistingDelegate(delegateParams.getAccountId(), delegateParams.getHostName(),
        delegateParams.isNg(), delegateParams.getDelegateType(), delegateParams.getIp());

    if (existingDelegate != null && existingDelegate.getStatus() == DelegateInstanceStatus.DELETED) {
      broadcasterFactory.lookup(STREAM_DELEGATE + delegateParams.getAccountId(), true)
          .broadcast(SELF_DESTRUCT + existingDelegate.getUuid());
      log.warn(
          "Sending self destruct command from register delegate parameters because the existing delegate has status deleted.");
      return DelegateRegisterResponse.builder().action(DelegateRegisterResponse.Action.SELF_DESTRUCT).build();
    }

    log.info("Registering delegate for Hostname: {} IP: {}", delegateParams.getHostName(), delegateParams.getIp());

    // publish delegateType metrics at account level.
    String delegateTypeMetric = delegateParams.isImmutable() ? IMMUTABLE_DELEGATES : MUTABLE_DELEGATES;
    delegateMetricsService.recordDelegateMetricsPerAccount(delegateParams.getAccountId(), delegateTypeMetric);

    String delegateGroupId = delegateParams.getDelegateGroupId();
    if (isBlank(delegateGroupId) && isNotBlank(delegateParams.getDelegateGroupName())) {
      final DelegateGroup delegateGroup =
          upsertDelegateGroup(delegateParams.getDelegateGroupName(), delegateParams.getAccountId(), null);
      delegateGroupId = delegateGroup.getUuid();
    }

    String delegateGroupName = delegateParams.getDelegateGroupName();

    // old ng delegates will be using accountKey as token, and hence we don't have org/project identifiers from the
    // global context thread
    String orgIdentifier = delegateParams.getOrgIdentifier() != null
        ? delegateParams.getOrgIdentifier()
        : getOrgIdentifierUsingTokenFromGlobalContext(delegateParams.getAccountId()).orElse(null);
    String projectIdentifier = delegateParams.getProjectIdentifier() != null
        ? delegateParams.getProjectIdentifier()
        : getProjectIdentifierUsingTokenFromGlobalContext(delegateParams.getAccountId()).orElse(null);

    Optional<String> delegateTokenName = getDelegateTokenNameFromGlobalContext();

    // tokenName here will be used for auditing the delegate register event
    DelegateSetupDetails delegateSetupDetails = DelegateSetupDetails.builder()
                                                    .name(delegateParams.getDelegateName())
                                                    .hostName(delegateParams.getHostName())
                                                    .orgIdentifier(orgIdentifier)
                                                    .projectIdentifier(projectIdentifier)
                                                    .description(delegateParams.getDescription())
                                                    .delegateType(delegateParams.getDelegateType())
                                                    .tokenName(delegateTokenName.orElse(null))
                                                    .build();

    // TODO: ARPIT for cg grouped delegates we should save tags only in delegateGroup

    if (delegateParams.isNg()) {
      final DelegateGroup delegateGroup =
          upsertDelegateGroup(delegateParams.getDelegateName(), delegateParams.getAccountId(), delegateSetupDetails);
      delegateGroupId = delegateGroup.getUuid();
      delegateGroupName = delegateGroup.getName();
    }

    if (isNotBlank(delegateGroupId) && isNotEmpty(delegateParams.getTags())) {
      persistence.update(persistence.createQuery(DelegateGroup.class).filter(DelegateGroupKeys.uuid, delegateGroupId),
          persistence.createUpdateOperations(DelegateGroup.class)
              .set(DelegateGroupKeys.tags, new HashSet<>(delegateParams.getTags())));
    }

    final DelegateEntityOwner owner = DelegateEntityOwnerHelper.buildOwner(orgIdentifier, projectIdentifier);

    final String delegateProfileId = delegateParams.getDelegateProfileId();
    try {
      validateDelegateProfileId(delegateParams.getAccountId(), delegateProfileId);
    } catch (InvalidRequestException e) {
      log.warn("No delegate configuration (profile) with id {} exists: {}", delegateProfileId, e);
    }

    final Delegate delegate = Delegate.builder()
                                  .uuid(delegateParams.getDelegateId())
                                  .accountId(delegateParams.getAccountId())
                                  .owner(owner)
                                  .ng(delegateParams.isNg())
                                  .description(delegateParams.getDescription())
                                  .ip(delegateParams.getIp())
                                  .hostName(delegateParams.getHostName())
                                  .delegateGroupName(isNotBlank(delegateGroupName) ? delegateGroupName : null)
                                  .delegateGroupId(isNotBlank(delegateGroupId) ? delegateGroupId : null)
                                  .delegateName(delegateParams.getDelegateName())
                                  .delegateProfileId(delegateProfileId)
                                  .lastHeartBeat(delegateParams.getLastHeartBeat())
                                  .version(delegateParams.getVersion())
                                  .sequenceNum(delegateParams.getSequenceNum())
                                  .delegateType(delegateParams.getDelegateType())
                                  .supportedTaskTypes(delegateParams.getSupportedTaskTypes())
                                  .delegateRandomToken(delegateParams.getDelegateRandomToken())
                                  .keepAlivePacket(delegateParams.isKeepAlivePacket())
                                  // if delegate is ng then save tags only in delegate group.
                                  .tags(delegateParams.isNg() ? null : delegateParams.getTags())
                                  .polllingModeEnabled(delegateParams.isPollingModeEnabled())
                                  .proxy(delegateParams.isProxy())
                                  .sampleDelegate(delegateParams.isSampleDelegate())
                                  .currentlyExecutingDelegateTasks(delegateParams.getCurrentlyExecutingDelegateTasks())
                                  .ceEnabled(delegateParams.isCeEnabled())
                                  .delegateTokenName(delegateTokenName.orElse(null))
                                  .heartbeatAsObject(delegateParams.isHeartbeatAsObject())
                                  .immutable(delegateParams.isImmutable())
                                  .mtls(isConnectedUsingMtls)
                                  .build();

    if (ECS.equals(delegateParams.getDelegateType())) {
      DelegateRegisterResponse delegateRegisterResponse =
          registerResponseFromDelegate(handleEcsDelegateRequest(delegate));
      if (delegateRegisterResponse != null) {
        delegateTelemetryPublisher.sendTelemetryTrackEvents(
            delegate.getAccountId(), ECS, delegate.isNg(), DELEGATE_REGISTERED_EVENT);
      }
      return delegateRegisterResponse;
    } else {
      return registerResponseFromDelegate(upsertDelegateOperation(existingDelegate, delegate, delegateSetupDetails));
    }
  }

  private Delegate getExistingDelegate(
      final String accountId, final String hostName, final boolean ng, final String delegateType, final String ip) {
    final Query<Delegate> delegateQuery = persistence.createQuery(Delegate.class)
                                              .filter(DelegateKeys.accountId, accountId)
                                              .filter(DelegateKeys.hostName, hostName);
    // For delegates running in a kubernetes cluster we include lowercase account ID in the hostname to identify it.
    // We ignore IP address because that can change with every restart of the pod.
    if (!(ng && KUBERNETES.equals(delegateType)) && !hostName.contains(getAccountIdentifier(accountId))) {
      delegateQuery.filter(DelegateKeys.ip, ip);
    }

    return delegateQuery.project(DelegateKeys.status, true)
        .project(DelegateKeys.delegateProfileId, true)
        .project(DelegateKeys.ng, true)
        .project(DelegateKeys.hostName, true)
        .project(DelegateKeys.owner, true)
        .project(DelegateKeys.delegateGroupName, true)
        .project(DelegateKeys.description, true)
        .get();
  }

  @Override
  public void unregister(final String accountId, final DelegateUnregisterRequest request) {
    final Delegate existingDelegate = getExistingDelegate(
        accountId, request.getHostName(), request.isNg(), request.getDelegateType(), request.getIpAddress());

    if (existingDelegate != null) {
      log.info("Removing delegate instance {} from delegate {}", request.getHostName(), request.getDelegateId());
      persistence.delete(existingDelegate);
      sendUnregisterDelegateAuditEvent(existingDelegate, accountId);
    } else {
      log.warn("Delegate instance {} doesn't exist for {}, nothing to remove", request.getHostName(),
          request.getDelegateId());
    }
    delegateConnectionDao.list(accountId, request.getDelegateId())
        .forEach(connection -> delegateDisconnected(accountId, request.getDelegateId(), connection.getUuid()));
  }

  @VisibleForTesting
  Delegate upsertDelegateOperation(Delegate existingDelegate, Delegate delegate) {
    return upsertDelegateOperation(existingDelegate, delegate, null);
  }

  @VisibleForTesting
  Delegate upsertDelegateOperation(
      Delegate existingDelegate, Delegate delegate, DelegateSetupDetails delegateSetupDetails) {
    long delegateHeartbeat = delegate.getLastHeartBeat();
    long now = clock.millis();
    long skew = Math.abs(now - delegateHeartbeat);
    if (skew > TimeUnit.MINUTES.toMillis(2L)) {
      log.debug("Delegate {} has clock skew of {}", delegate.getUuid(), Misc.getDurationString(skew));
    }
    delegate.setLastHeartBeat(now);
    delegate.setValidUntil(Date.from(OffsetDateTime.now().plusDays(Delegate.TTL.toDays()).toInstant()));

    if (isGroupedCgDelegate(delegate)) {
      updateDelegateWithConfigFromGroup(delegate);
    }

    Delegate registeredDelegate;
    if (existingDelegate == null) {
      log.info("No existing delegate, adding for account {}: Hostname: {} IP: {}", delegate.getAccountId(),
          delegate.getHostName(), delegate.getIp());

      createAuditHeaderForDelegateRegistration(delegate.getHostName());

      registeredDelegate = add(delegate);
      sendRegisterDelegateAuditEvent(delegate, delegateSetupDetails, delegate.getAccountId());

      delegateTelemetryPublisher.sendTelemetryTrackEvents(
          delegate.getAccountId(), delegate.getDelegateType(), delegate.isNg(), DELEGATE_REGISTERED_EVENT);
    } else {
      log.debug("Delegate exists, updating: {}", delegate.getUuid());
      delegate.setUuid(existingDelegate.getUuid());
      delegate.setStatus(existingDelegate.getStatus());
      delegate.setDelegateProfileId(existingDelegate.getDelegateProfileId());
      if (isEmpty(delegate.getDescription())) {
        delegate.setDescription(existingDelegate.getDescription());
      }
      if (ECS.equals(delegate.getDelegateType())) {
        registeredDelegate = updateEcsDelegateInstance(delegate);
      } else {
        registeredDelegate = update(delegate);
      }
    }

    // Not needed to be done when polling is enabled for delegate
    if (isDelegateWithoutPollingEnabled(delegate)) {
      if (delegate.isHeartbeatAsObject()) {
        broadcastDelegateHeartBeatResponse(delegate, registeredDelegate);
      } else {
        // Broadcast Message containing, DelegateId and SeqNum (if applicable)
        StringBuilder message = new StringBuilder(128).append("[X]").append(delegate.getUuid());
        updateBroadcastMessageIfEcsDelegate(message, delegate, registeredDelegate);
        broadcasterFactory.lookup(STREAM_DELEGATE + delegate.getAccountId(), true).broadcast(message.toString());
      }
    }
    return registeredDelegate;
  }

  private void broadcastDelegateHeartBeatResponse(Delegate delegate, Delegate registeredDelegate) {
    DelegateHeartbeatResponseStreamingBuilder builder = DelegateHeartbeatResponseStreaming.builder()
                                                            .delegateId(delegate.getUuid())
                                                            .status(delegate.getStatus().toString())
                                                            .useCdn(delegate.isUseCdn());
    if (ECS.equals(delegate.getDelegateType())) {
      String hostName = getDelegateHostNameByRemovingSeqNum(registeredDelegate);
      String seqNum = getDelegateSeqNumFromHostName(registeredDelegate);
      DelegateSequenceConfig sequenceConfig =
          getDelegateSequenceConfig(delegate.getAccountId(), hostName, Integer.parseInt(seqNum));
      registeredDelegate.setDelegateRandomToken(sequenceConfig.getDelegateToken());
      registeredDelegate.setSequenceNum(sequenceConfig.getSequenceNum().toString());
      builder.delegateRandomToken(sequenceConfig.getDelegateToken())
          .sequenceNumber(sequenceConfig.getSequenceNum().toString());
    }
    long now = clock.millis();
    builder.responseSentAt(now);
    DelegateHeartbeatResponseStreaming response = builder.build();
    broadcasterFactory.lookup(STREAM_DELEGATE + delegate.getAccountId(), true).broadcast(response);
  }

  private boolean isGroupedCgDelegate(final Delegate delegate) {
    return !delegate.isNg() && isNotBlank(delegate.getDelegateGroupName());
  }

  private AuditHeader createAuditHeaderForDelegateRegistration(String delegateHostName) {
    AuditHeader.Builder builder = anAuditHeader();
    builder.withCreatedAt(System.currentTimeMillis())
        .withCreatedBy(EmbeddedUser.builder().name(delegateHostName).uuid("delegate").build())
        .withRemoteUser(anUser().name(delegateHostName).uuid("delegate").build())
        .withRequestMethod(HttpMethod.POST)
        .withRequestTime(System.currentTimeMillis())
        .withUrl("/agent/delegates");

    return auditHelper.create(builder.build());
  }

  private void updateBroadcastMessageIfEcsDelegate(
      StringBuilder message, Delegate delegate, Delegate registeredDelegate) {
    if (ECS.equals(delegate.getDelegateType())) {
      String hostName = getDelegateHostNameByRemovingSeqNum(registeredDelegate);
      String seqNum = getDelegateSeqNumFromHostName(registeredDelegate);
      DelegateSequenceConfig sequenceConfig =
          getDelegateSequenceConfig(delegate.getAccountId(), hostName, Integer.parseInt(seqNum));
      registeredDelegate.setDelegateRandomToken(sequenceConfig.getDelegateToken());
      registeredDelegate.setSequenceNum(sequenceConfig.getSequenceNum().toString());
      message.append("[TOKEN]")
          .append(sequenceConfig.getDelegateToken())
          .append("[SEQ]")
          .append(sequenceConfig.getSequenceNum());

      log.info("^^^^SEQ: " + message.toString());
    }
  }

  private DelegateRegisterResponse registerResponseFromDelegate(Delegate delegate) {
    if (delegate == null) {
      return null;
    }

    return DelegateRegisterResponse.builder()
        .delegateId(delegate.getUuid())
        .sequenceNum(delegate.getSequenceNum())
        .delegateRandomToken(delegate.getDelegateRandomToken())
        .build();
  }

  @VisibleForTesting
  DelegateSequenceConfig getDelegateSequenceConfig(String accountId, String hostName, Integer seqNum) {
    Query<DelegateSequenceConfig> delegateSequenceQuery = persistence.createQuery(DelegateSequenceConfig.class)
                                                              .filter(DelegateSequenceConfigKeys.accountId, accountId)
                                                              .filter(DelegateSequenceConfigKeys.hostName, hostName);

    if (seqNum != null) {
      delegateSequenceQuery.filter(DelegateSequenceConfigKeys.sequenceNum, seqNum);
    }

    return delegateSequenceQuery.project(DelegateSequenceConfigKeys.accountId, true)
        .project(DelegateSequenceConfigKeys.sequenceNum, true)
        .project(DelegateSequenceConfigKeys.hostName, true)
        .project(DelegateSequenceConfigKeys.delegateToken, true)
        .get();
  }

  @Override
  public DelegateProfileParams checkForProfile(
      String accountId, String delegateId, String profileId, long lastUpdatedAt) {
    if (configurationController.isNotPrimary()) {
      return null;
    }

    log.info("Checking delegate profile. Previous profile [{}] updated at {}", profileId, lastUpdatedAt);
    Delegate delegate = delegateCache.get(accountId, delegateId, true);

    if (delegate == null || DelegateInstanceStatus.ENABLED != delegate.getStatus()) {
      return null;
    }

    if (isNotBlank(profileId) && isBlank(delegate.getDelegateProfileId())) {
      return DelegateProfileParams.builder().profileId("NONE").build();
    }

    if (isNotBlank(delegate.getDelegateProfileId())) {
      DelegateProfile profile = delegateProfileService.get(accountId, delegate.getDelegateProfileId());
      if (profile != null && (!profile.getUuid().equals(profileId) || profile.getLastUpdatedAt() > lastUpdatedAt)) {
        Map<String, Object> context = new HashMap<>();
        context.put("secrets",
            SecretFunctor.builder()
                .managerDecryptionService(managerDecryptionService)
                .secretManager(secretManager)
                .accountId(accountId)
                .build());
        String scriptContent = evaluator.substitute(profile.getStartupScript(), context);
        return DelegateProfileParams.builder()
            .profileId(profile.getUuid())
            .name(profile.getName())
            .profileLastUpdatedAt(profile.getLastUpdatedAt())
            .scriptContent(scriptContent)
            .build();
      }
    }
    return null;
  }

  @Override
  public void saveProfileResult(String accountId, String delegateId, boolean error, FileBucket fileBucket,
      InputStream uploadedInputStream, FormDataContentDisposition fileDetail) {
    Delegate delegate = delegateCache.get(accountId, delegateId, true);

    FileMetadata fileMetadata = FileMetadata.builder()
                                    .fileName(new File(fileDetail.getFileName()).getName())
                                    .accountId(accountId)
                                    .fileUuid(generateUuid())
                                    .build();
    String fileId = fileService.saveFile(fileMetadata,
        new BoundedInputStream(uploadedInputStream, mainConfiguration.getFileUploadLimits().getProfileResultLimit()),
        fileBucket);

    String previousProfileResult = delegate.getProfileResult();

    persistence.update(persistence.createQuery(Delegate.class)
                           .filter(DelegateKeys.accountId, accountId)
                           .filter(DelegateKeys.uuid, delegateId),
        persistence.createUpdateOperations(Delegate.class)
            .set(DelegateKeys.profileResult, fileId)
            .set(DelegateKeys.profileError, error)
            .set(DelegateKeys.profileExecutedAt, clock.millis()));

    if (isNotBlank(previousProfileResult)) {
      fileService.deleteFile(previousProfileResult, FileBucket.PROFILE_RESULTS);
    }
  }

  @Override
  public String getProfileResult(String accountId, String delegateId) {
    Delegate delegate = delegateCache.get(accountId, delegateId, false);

    String profileResultFileId = delegate.getProfileResult();

    if (isBlank(profileResultFileId)) {
      return "No profile result available for " + delegate.getHostName();
    }

    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
      fileService.downloadToStream(profileResultFileId, os, FileBucket.PROFILE_RESULTS);
      os.flush();
      return new String(os.toByteArray(), UTF_8);
    } catch (Exception e) {
      throw new GeneralException("Profile execution log temporarily unavailable. Try again in a few moments.");
    }
  }

  @Override
  public String obtainDelegateName(Delegate delegate) {
    if (delegate == null) {
      return "";
    }

    String delegateName = delegate.getDelegateName();
    if (!isBlank(delegateName)) {
      return delegateName;
    }

    String hostName = delegate.getHostName();
    if (!isBlank(hostName)) {
      return hostName;
    }

    return delegate.getUuid();
  }

  @Override
  public String obtainDelegateName(String accountId, String delegateId, boolean forceRefresh) {
    if (isBlank(accountId) || isBlank(delegateId)) {
      return "";
    }

    Delegate delegate = delegateCache.get(accountId, delegateId, forceRefresh);
    if (delegate == null) {
      return delegateId;
    }

    return obtainDelegateName(delegate);
  }

  @Override
  public List<String> obtainDelegateIdsUsingName(String accountId, String delegateName) {
    try {
      return persistence.createQuery(Delegate.class)
          .filter(DelegateKeys.accountId, accountId)
          .filter(DelegateKeys.delegateName, delegateName)
          .filter(DelegateKeys.ng, true)
          .asKeyList()
          .stream()
          .map(key -> (String) key.getId())
          .collect(toList());
    } catch (Exception e) {
      log.error("Could not get delegates from DB.", e);
      return null;
    }
  }

  @VisibleForTesting
  public List<CapabilityRequirement> createCapabilityRequirementInstances(
      String accountId, List<ExecutionCapability> agentCapabilities) {
    List<CapabilityRequirement> capabilityRequirements = new ArrayList<>();
    for (ExecutionCapability agentCapability : agentCapabilities) {
      CapabilityRequirement capabilityRequirement =
          capabilityService.buildCapabilityRequirement(accountId, agentCapability);

      if (capabilityRequirement != null) {
        capabilityRequirements.add(capabilityRequirement);
      }
    }

    return capabilityRequirements;
  }

  @Override
  public List<DelegateInitializationDetails> obtainDelegateInitializationDetails(
      String accountId, List<String> delegateIds) {
    List<DelegateInitializationDetails> delegateInitializationDetails = new ArrayList<>();

    delegateIds.forEach(
        delegateId -> delegateInitializationDetails.add(getDelegateInitializationDetails(accountId, delegateId)));

    return delegateInitializationDetails;
  }

  @Override
  public boolean validateThatDelegateNameIsUnique(String accountId, String delegateName) {
    Delegate delegate = persistence.createQuery(Delegate.class)
                            .filter(DelegateKeys.accountId, accountId)
                            .filter(DelegateKeys.delegateName, delegateName)
                            .get();
    if (delegate == null) {
      return true;
    }
    return false;
  }

  @Override
  public void delegateDisconnected(String accountId, String delegateId, String delegateConnectionId) {
    log.info("Delegate connection {} disconnected for delegate {}", delegateConnectionId, delegateId);
    delegateConnectionDao.delegateDisconnected(accountId, delegateConnectionId);
    subject.fireInform(DelegateObserver::onDisconnected, accountId, delegateId);
    Delegate delegate = delegateCache.get(accountId, delegateId, false);
    delegateMetricsService.recordDelegateMetrics(delegate, DELEGATE_DISCONNECTED);
    remoteObserverInformer.sendEvent(
        ReflectionUtils.getMethod(DelegateObserver.class, "onDisconnected", String.class, String.class),
        DelegateServiceImpl.class, accountId, delegateId);
  }

  @Override
  public boolean filter(String accountId, String delegateId) {
    Delegate delegate = delegateCache.get(accountId, delegateId, false);
    return delegate != null && StringUtils.equals(delegate.getAccountId(), accountId);
  }

  @Override
  public void deleteByAccountId(String accountId) {
    persistence.delete(persistence.createQuery(Delegate.class).filter(DelegateKeys.accountId, accountId));
  }

  //------ Start: ECS Delegate Specific Methods

  /**
   * Delegate keepAlive and Registration requests will be handled here
   */
  @Override
  public Delegate handleEcsDelegateRequest(final Delegate delegate) {
    if (delegate.isKeepAlivePacket()) {
      handleEcsDelegateKeepAlivePacket(delegate);
      return null;
    }
    final Delegate registeredDelegate = handleEcsDelegateRegistration(delegate);
    updateExistingDelegateWithSequenceConfigData(registeredDelegate);
    registeredDelegate.setUseCdn(mainConfiguration.useCdnForDelegateStorage());
    registeredDelegate.setUseJreVersion(getTargetJreVersion(delegate.getAccountId()));

    return registeredDelegate;
  }

  @Override
  public DelegateGroup upsertDelegateGroup(String name, String accountId, DelegateSetupDetails delegateSetupDetails) {
    boolean isNg = delegateSetupDetails != null;
    String delegateGroupIdentifier = getDelegateGroupIdentifier(name, delegateSetupDetails);
    if (isNg) {
      try {
        delegateSetupDetails.setIdentifier(delegateGroupIdentifier);
        NGUtils.validate(delegateSetupDetails);
      } catch (JerseyViolationException exception) {
        throw new InvalidRequestException(getMessage(exception));
      }
    }
    String description = delegateSetupDetails != null ? delegateSetupDetails.getDescription() : null;
    String orgIdentifier = delegateSetupDetails != null ? delegateSetupDetails.getOrgIdentifier() : null;
    String projectIdentifier = delegateSetupDetails != null ? delegateSetupDetails.getProjectIdentifier() : null;
    String delegateType = delegateSetupDetails != null ? delegateSetupDetails.getDelegateType() : KUBERNETES;
    DelegateSizeDetails sizeDetails = delegateSetupDetails != null
        ? fetchAvailableSizes()
              .stream()
              .filter(size -> size.getSize() == delegateSetupDetails.getSize())
              .findFirst()
              .orElse(null)
        : null;
    K8sConfigDetails k8sConfigDetails =
        delegateSetupDetails != null ? delegateSetupDetails.getK8sConfigDetails() : null;
    final Set<String> tags = delegateSetupDetails != null ? delegateSetupDetails.getTags() : null;

    DelegateEntityOwner owner = DelegateEntityOwnerHelper.buildOwner(orgIdentifier, projectIdentifier);

    Query<DelegateGroup> query = this.persistence.createQuery(DelegateGroup.class)
                                     .filter(DelegateGroupKeys.accountId, accountId)
                                     .filter(DelegateGroupKeys.ng, isNg)
                                     .filter(DelegateGroupKeys.name, name);

    DelegateGroup existingEntity = query.get();

    if (existingEntity != null && !matchOwners(existingEntity.getOwner(), owner)) {
      log.error(
          "Unable to create delegate group. Delegate with same name exists. Delegate name must be unique across account.");
      throw new InvalidRequestException(
          "Unable to create delegate group. Delegate with same name exists. Delegate name must be unique across account.");
    }

    // this statement is here because of identifier migration where we used normalized uuid for existing groups
    if (existingEntity != null && uuidToIdentifier(existingEntity.getUuid()).equals(existingEntity.getIdentifier())) {
      delegateGroupIdentifier = existingEntity.getIdentifier();
    }

    UpdateOperations<DelegateGroup> updateOperations =
        this.persistence.createUpdateOperations(DelegateGroup.class)
            .setOnInsert(DelegateGroupKeys.uuid, generateUuid())
            .setOnInsert(DelegateGroupKeys.identifier, delegateGroupIdentifier)
            .set(DelegateGroupKeys.name, name)
            .set(DelegateGroupKeys.accountId, accountId)
            .set(DelegateGroupKeys.ng, isNg)
            .set(DelegateGroupKeys.delegateType, delegateType);

    if (k8sConfigDetails != null) {
      setUnset(updateOperations, DelegateGroupKeys.k8sConfigDetails, k8sConfigDetails);
    }

    setUnset(updateOperations, DelegateGroupKeys.owner, owner);
    setUnset(updateOperations, DelegateGroupKeys.description, description);
    setUnset(updateOperations, DelegateGroupKeys.tags, tags);

    if (sizeDetails != null) {
      setUnset(updateOperations, DelegateGroupKeys.sizeDetails, sizeDetails);
    }
    return persistence.upsert(query, updateOperations, HPersistence.upsertReturnNewOptions);
  }

  @Override
  public long getCountOfRegisteredDelegates(String accountId) {
    return persistence.createQuery(Delegate.class)
        .filter(DelegateKeys.accountId, accountId)
        .field(DelegateKeys.status)
        .notEqual(DelegateInstanceStatus.DELETED)
        .count();
  }

  @Override
  public long getCountOfConnectedDelegates(String accountId) {
    return persistence.createQuery(Delegate.class)
        .filter(DelegateKeys.accountId, accountId)
        .field(DelegateKeys.status)
        .notEqual(DelegateInstanceStatus.DELETED)
        .field(DelegateKeys.lastHeartBeat)
        .lessThan(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30))
        .count();
  }

  private boolean isImmutableDelegate(final String accountId, final String delegateType) {
    return featureFlagService.isEnabled(USE_IMMUTABLE_DELEGATE, accountId)
        && (KUBERNETES.equals(delegateType) || CE_KUBERNETES.equals(delegateType));
  }

  @Override
  public void updateLastExpiredEventHeartbeatTime(
      long lastExpiredEventHeartbeatTime, String delegateId, String accountId) {
    final Query<Delegate> delegateFindQuery = persistence.createQuery(Delegate.class)
                                                  .field(DelegateKeys.uuid)
                                                  .equal(delegateId)
                                                  .field(DelegateKeys.accountId)
                                                  .equal(accountId);
    persistence.update(delegateFindQuery,
        persistence.createUpdateOperations(Delegate.class)
            .set(DelegateKeys.lastExpiredEventHeartbeatTime, lastExpiredEventHeartbeatTime));
  }

  @NotNull
  private String getMessage(JerseyViolationException exception) {
    return "Fields "
        + exception.getConstraintViolations()
              .stream()
              .map(c -> ((PathImpl) c.getPropertyPath()).getLeafNode().getName())
              .reduce("", (i, j) -> i + " <" + j + "> ")
        + " did not pass validation checks: "
        + exception.getConstraintViolations()
              .stream()
              .map(ConstraintViolation::getMessage)
              .reduce("", (i, j) -> i + " <" + j + "> ");
  }

  private String getDelegateGroupIdentifier(String name, DelegateSetupDetails delegateSetupDetails) {
    if (delegateSetupDetails != null && isNotBlank(delegateSetupDetails.getIdentifier())) {
      return delegateSetupDetails.getIdentifier();
    } else if (delegateSetupDetails != null && isBlank(delegateSetupDetails.getIdentifier())) {
      return normalizeIdentifier(delegateSetupDetails.getName());
    } else {
      return normalizeIdentifier(name);
    }
  }

  public void registerHeartbeat(
      String accountId, String delegateId, DelegateConnectionHeartbeat heartbeat, ConnectionMode connectionMode) {
    String version = heartbeat.getVersion();
    if (isEmpty(version)) {
      version = accountService.getAccountPrimaryDelegateVersion(accountId);
    }
    DelegateConnection previousDelegateConnection = delegateConnectionDao.upsertCurrentConnection(
        accountId, delegateId, heartbeat.getDelegateConnectionId(), version, heartbeat.getLocation());

    Delegate delegate = delegateCache.get(accountId, delegateId, false);
    if (previousDelegateConnection == null) {
      DelegateConnection existingConnection = delegateConnectionDao.findAndDeletePreviousConnections(
          accountId, delegateId, heartbeat.getDelegateConnectionId(), heartbeat.getVersion());
      if (existingConnection != null) {
        UUID currentUUID = convertFromBase64(heartbeat.getDelegateConnectionId());
        UUID existingUUID = convertFromBase64(existingConnection.getUuid());
        if (existingUUID.timestamp() > currentUUID.timestamp()) {
          if (DelegateType.SHELL_SCRIPT.equals(delegate.getDelegateType())) {
            boolean notSameLocationForShellScriptDelegate = isNotEmpty(heartbeat.getLocation())
                && isNotEmpty(existingConnection.getLocation())
                && !heartbeat.getLocation().equals(existingConnection.getLocation());
            if (notSameLocationForShellScriptDelegate) {
              log.error(
                  "Newer delegate connection found for the delegate id! Will initiate self destruct sequence for the current delegate.");
              destroyTheCurrentDelegate(accountId, delegateId, heartbeat.getDelegateConnectionId(), connectionMode);
              delegateConnectionDao.replaceWithNewerConnection(heartbeat.getDelegateConnectionId(), existingConnection);
            } else {
              log.error("Two delegates with the same identity");
            }
          }
        } else {
          delegateMetricsService.recordDelegateMetrics(delegate, DELEGATE_RESTARTED);
          log.error("Delegate restarted");
        }
      }
    }
  }

  /**
   * ECS delegate sends keepAlive request every 20 secs. KeepAlive request is a frequent and light weight
   * mode for indicating that delegate is active.
   * <p>
   * We just update "lastUpdatedAt" field with latest time for DelegateSequenceConfig associated with delegate,
   * so we can found stale config (not updated in last 100 secs) when we need to reuse it for new delegate
   * registration.
   */
  @VisibleForTesting
  void handleEcsDelegateKeepAlivePacket(final Delegate delegate) {
    log.debug("Handling Keep alive packet ");
    if (isBlank(delegate.getHostName()) || isBlank(delegate.getDelegateRandomToken()) || isBlank(delegate.getUuid())
        || isBlank(delegate.getSequenceNum())) {
      return;
    }

    Delegate existingDelegate =
        getDelegateUsingSequenceNum(delegate.getAccountId(), delegate.getHostName(), delegate.getSequenceNum());
    if (existingDelegate == null) {
      return;
    }

    DelegateSequenceConfig config = getDelegateSequenceConfig(
        delegate.getAccountId(), delegate.getHostName(), Integer.parseInt(delegate.getSequenceNum()));

    if (config != null && config.getDelegateToken().equals(delegate.getDelegateRandomToken())) {
      Query<DelegateSequenceConfig> sequenceConfigQuery =
          persistence.createQuery(DelegateSequenceConfig.class).filter(ID_KEY, config.getUuid());
      persistence.update(sequenceConfigQuery,
          persistence.createUpdateOperations(DelegateSequenceConfig.class)
              .set(DelegateSequenceConfigKeys.delegateToken, delegate.getDelegateRandomToken()));
    }
  }

  @VisibleForTesting
  Delegate handleEcsDelegateRegistration(final Delegate delegate) {
    // SCENARIO 1: Received delegateId with the request and delegate exists in DB.
    // Just update same existing delegate

    if (delegate.getUuid() != null && isValidSeqNum(delegate.getSequenceNum())
        && checkForValidTokenIfPresent(delegate)) {
      final Delegate registeredDelegate = handleECSRegistrationUsingID(delegate);
      if (registeredDelegate != null) {
        return registeredDelegate;
      }
    }

    // can not proceed unless we receive valid token
    if (isBlank(delegate.getDelegateRandomToken()) || "null".equalsIgnoreCase(delegate.getDelegateRandomToken())) {
      throw new GeneralException("Received invalid token from ECS delegate");
    }

    // SCENARIO 2: Delegate passed sequenceNum & delegateToken but not UUID.
    // So delegate was registered earlier but may be got restarted and trying re-register.
    if (isValidSeqNum(delegate.getSequenceNum()) && isNotBlank(delegate.getDelegateRandomToken())) {
      final Delegate registeredDelegate = handleECSRegistrationUsingSeqNumAndToken(delegate);
      if (registeredDelegate != null) {
        return registeredDelegate;
      }
    }

    // SCENARIO 3: Create new SequenceNum for delegate.
    // We will reach here in 2 scenarios,
    // 1. Delegate did not pass any sequenceNum or delegateToken. (This is first time delegate is registering after
    // start up or disk file delegate writes to, got deleted).

    // 2. Delegate passed seqNum & delegateToken, but We got DuplicateKeyException in SCENARIO 2
    // In any of these cases, it will be treated as fresh registration and new sequenceNum will be generated.
    return registerDelegateWithNewSequenceGeneration(delegate);
  }

  @VisibleForTesting
  boolean checkForValidTokenIfPresent(Delegate delegate) {
    DelegateSequenceConfig config = getDelegateSequenceConfig(
        delegate.getAccountId(), delegate.getHostName(), Integer.parseInt(delegate.getSequenceNum()));
    return config != null && config.getDelegateToken().equals(delegate.getDelegateRandomToken());
  }

  /**
   * Delegate sent token and seqNum but null UUID.
   * 1. See if DelegateSequenceConfig record with same {accId, SeqNum} has same token as passed by delegate.
   * If yes,
   * - get delegate associated with this DelegateSequenceConfig if exists and update it.
   * - if delegate does not present in db, create a new record (init it with config from similar delegate and
   * create record)
   * <p>
   * IF No,
   * - Means that seqNum has been acquired by another delegate.
   * - Generate a new SeqNum and create delegate record using it (init it with config from similar delegate and
   * create record).
   */
  @VisibleForTesting
  Delegate handleECSRegistrationUsingSeqNumAndToken(Delegate delegate) {
    log.info("Delegate sent seqNum : " + delegate.getSequenceNum() + ", and DelegateToken"
        + delegate.getDelegateRandomToken());

    DelegateSequenceConfig sequenceConfig = getDelegateSequenceConfig(
        delegate.getAccountId(), delegate.getHostName(), Integer.parseInt(delegate.getSequenceNum()));

    Delegate existingDelegate = null;
    boolean delegateConfigMatches = false;
    // SequenceConfig found with same {HostName, AccountId, SequenceNum, DelegateToken}.
    // Its same delegate sending request with valid data. Find actual delegate record using this
    // DelegateSequenceConfig
    if (seqNumAndTokenMatchesConfig(delegate, sequenceConfig)) {
      delegateConfigMatches = true;
      existingDelegate = getDelegateUsingSequenceNum(
          sequenceConfig.getAccountId(), sequenceConfig.getHostName(), sequenceConfig.getSequenceNum().toString());
    }

    // No Existing delegate was found, so create new delegate record on manager side,
    // using {seqNum, delegateToken} passed by delegate.
    if (existingDelegate == null) {
      try {
        DelegateSequenceConfig config = delegateConfigMatches
            ? sequenceConfig
            : generateNewSeqenceConfig(delegate, Integer.parseInt(delegate.getSequenceNum()));

        String hostNameWithSeqNum =
            getHostNameToBeUsedForECSDelegate(config.getHostName(), config.getSequenceNum().toString());
        delegate.setHostName(hostNameWithSeqNum);

        // Init this delegate with {TAG/SCOPE/PROFILE} config reading from similar delegate
        updateDelegateWithConfigFromGroup(delegate);

        return upsertDelegateOperation(null, delegate);
      } catch (DuplicateKeyException e) {
        log.warn(
            "SequenceNum passed by delegate has been assigned to a new delegate. will regenerate new sequenceNum.");
      }
    } else {
      // Existing delegate was found, so just update it.
      return upsertDelegateOperation(existingDelegate, delegate);
    }

    return null;
  }

  @VisibleForTesting
  boolean seqNumAndTokenMatchesConfig(Delegate delegate, DelegateSequenceConfig sequenceConfig) {
    return sequenceConfig != null && sequenceConfig.getSequenceNum() != null
        && isNotBlank(sequenceConfig.getDelegateToken())
        && sequenceConfig.getDelegateToken().equals(delegate.getDelegateRandomToken())
        && sequenceConfig.getSequenceNum().toString().equals(delegate.getSequenceNum());
  }

  /**
   * Get Delegate associated with {AccountId, HostName, SeqNum}
   */
  @VisibleForTesting
  Delegate getDelegateUsingSequenceNum(String accountId, String hostName, String seqNum) {
    Delegate existingDelegate;
    Query<Delegate> delegateQuery =
        persistence.createQuery(Delegate.class)
            .filter(DelegateKeys.accountId, accountId)
            .filter(DelegateSequenceConfigKeys.hostName, getHostNameToBeUsedForECSDelegate(hostName, seqNum));

    existingDelegate = delegateQuery.get();
    return existingDelegate;
  }

  /**
   * Get existing delegate having same {hostName (prefix without seqNum), AccId, type = ECS}
   * Copy {SCOPE/PROFILE/TAG/KEYWORDS/DESCRIPTION} config into new delegate being registered
   */
  @VisibleForTesting
  void updateDelegateWithConfigFromGroup(final Delegate delegate) {
    final List<Delegate> existingDelegates = getAllDelegatesMatchingGroupName(delegate);
    if (isNotEmpty(existingDelegates)) {
      updateNewDelegateWithExistingDelegate(delegate, existingDelegates.get(0));
    }
  }

  /**
   * Delegate send UUID, if record exists, just update same one.
   */
  @VisibleForTesting
  Delegate handleECSRegistrationUsingID(Delegate delegate) {
    Query<Delegate> delegateQuery =
        persistence.createQuery(Delegate.class).filter(DelegateKeys.uuid, delegate.getUuid());

    Delegate existingDelegate = delegateQuery.project(DelegateKeys.hostName, true)
                                    .project(DelegateKeys.status, true)
                                    .project(DelegateKeys.delegateProfileId, true)
                                    .project(DelegateKeys.description, true)
                                    .get();

    if (existingDelegate != null) {
      return upsertDelegateOperation(existingDelegate, delegate);
    }

    return null;
  }

  /**
   * Either
   * 1. find a stale DelegateSeqConfig (not updated for last 100 secs),
   * delete delegate associated with it and use this seqNum for new delegate registration.
   * <p>
   * 2. Else no such config exists from point 1, Create new SequenceConfig and associate with delegate.
   * (In both cases, we copy config {SCOPE/TAG/PROFILE} from existing delegates to this new delegate being registered)
   */
  @VisibleForTesting
  Delegate registerDelegateWithNewSequenceGeneration(Delegate delegate) {
    List<DelegateSequenceConfig> existingDelegateSequenceConfigs = getDelegateSequenceConfigs(delegate);

    // Find Inactive DelegateSequenceConfig with same Acc and hostName and delete associated delegate
    DelegateSequenceConfig config =
        getInactiveDelegateSequenceConfigToReplace(delegate, existingDelegateSequenceConfigs);

    if (config != null) {
      return upsertDelegateOperation(null, delegate);
    }

    // Could not find InactiveDelegateConfig, Create new SequenceConfig
    for (int i = 0; i < 3; i++) {
      try {
        config = addNewDelegateSequenceConfigRecord(delegate);
        String hostNameWithSeqNum =
            getHostNameToBeUsedForECSDelegate(delegate.getHostName(), config.getSequenceNum().toString());
        delegate.setHostName(hostNameWithSeqNum);

        // Init this delegate with TAG/SCOPE/PROFILE/KEYWORDS/DESCRIPTION config reading from similar delegate
        updateDelegateWithConfigFromGroup(delegate);

        return upsertDelegateOperation(null, delegate);
      } catch (Exception e) {
        log.warn("Attempt: " + i + " failed with DuplicateKeyException. Trying again" + e);
      }
    }
    // All 3 attempts of sequenceNum generation for delegate failed. Registration can not be completed.
    // Delegate will need to send request again
    throw new GeneralException("Failed to generate sequence number for Delegate");
  }

  /**
   * This method expects, you have already stripped off seqNum for delegate host name
   */
  @VisibleForTesting
  List<DelegateSequenceConfig> getDelegateSequenceConfigs(Delegate delegate) {
    Query<DelegateSequenceConfig> delegateSequenceConfigQuery =
        persistence.createQuery(DelegateSequenceConfig.class)
            .filter(DelegateSequenceConfigKeys.accountId, delegate.getAccountId())
            .filter(DelegateSequenceConfigKeys.hostName, delegate.getHostName());

    return delegateSequenceConfigQuery.project(ID_KEY, true)
        .project(DelegateSequenceConfigKeys.sequenceNum, true)
        .project(DelegateSequenceConfig.LAST_UPDATED_AT_KEY2, true)
        .project(DelegateSequenceConfig.ACCOUNT_ID_KEY2, true)
        .project(DelegateSequenceConfigKeys.hostName, true)
        .project(DelegateSequenceConfigKeys.delegateToken, true)
        .asList();
  }

  @VisibleForTesting
  DelegateSequenceConfig addNewDelegateSequenceConfigRecord(Delegate delegate) {
    Query<DelegateSequenceConfig> delegateSequenceConfigQuery =
        persistence.createQuery(DelegateSequenceConfig.class)
            .filter(DelegateSequenceConfig.ACCOUNT_ID_KEY2, delegate.getAccountId())
            .filter(DelegateSequenceConfigKeys.hostName, delegate.getHostName());

    List<DelegateSequenceConfig> existingDelegateSequenceConfigs =
        delegateSequenceConfigQuery.project(DelegateSequenceConfigKeys.sequenceNum, true)
            .project(DelegateSequenceConfig.LAST_UPDATED_AT_KEY2, true)
            .project(DelegateSequenceConfig.ACCOUNT_ID_KEY2, true)
            .project(DelegateSequenceConfigKeys.hostName, true)
            .project(DelegateSequenceConfigKeys.delegateToken, true)
            .asList();

    existingDelegateSequenceConfigs = existingDelegateSequenceConfigs.stream()
                                          .sorted(comparingInt(DelegateSequenceConfig::getSequenceNum))
                                          .collect(toList());

    int num = 0;
    for (DelegateSequenceConfig existingDelegateSequenceConfig : existingDelegateSequenceConfigs) {
      if (num < existingDelegateSequenceConfig.getSequenceNum()) {
        break;
      }
      num++;
    }

    delegate.setSequenceNum(String.valueOf(num));
    return generateNewSeqenceConfig(delegate, num);
  }

  @VisibleForTesting
  DelegateSequenceConfig getInactiveDelegateSequenceConfigToReplace(
      Delegate delegate, List<DelegateSequenceConfig> existingDelegateSequenceConfigs) {
    DelegateSequenceConfig config;
    try {
      Optional<DelegateSequenceConfig> optionalConfig =
          existingDelegateSequenceConfigs.stream()
              .filter(sequenceConfig
                  -> sequenceConfig.getLastUpdatedAt() < currentTimeMillis() - TimeUnit.SECONDS.toMillis(100))
              .findFirst();

      if (optionalConfig.isPresent()) {
        config = optionalConfig.get();

        Delegate existingInactiveDelegate = getDelegateUsingSequenceNum(
            delegate.getAccountId(), config.getHostName(), config.getSequenceNum().toString());

        if (existingInactiveDelegate != null) {
          // Before deleting existing one, copy {TAG/PROFILE/SCOPE} config into new delegate being registered
          // This needs to be done here as this may be the only delegate in db.
          updateNewDelegateWithExistingDelegate(delegate, existingInactiveDelegate);
          delete(existingInactiveDelegate.getAccountId(), existingInactiveDelegate.getUuid());
        }

        Query<DelegateSequenceConfig> sequenceConfigQuery =
            persistence.createQuery(DelegateSequenceConfig.class).filter("_id", config.getUuid());
        persistence.update(sequenceConfigQuery,
            persistence.createUpdateOperations(DelegateSequenceConfig.class)
                .set(DelegateSequenceConfigKeys.delegateToken, delegate.getDelegateRandomToken()));

        // Update delegate with seqNum and hostName
        delegate.setSequenceNum(config.getSequenceNum().toString());
        String hostNameWithSeqNum =
            getHostNameToBeUsedForECSDelegate(config.getHostName(), config.getSequenceNum().toString());
        delegate.setHostName(hostNameWithSeqNum);

        if (existingInactiveDelegate == null) {
          updateDelegateWithConfigFromGroup(delegate);
        }
        return config;
      }
    } catch (Exception e) {
      log.warn("Failed while updating delegateSequenceConfig with delegateToken: {}, DelegateId: {}",
          delegate.getDelegateRandomToken(), delegate.getUuid());
    }

    return null;
  }

  @Override
  public DelegateDTO listDelegateTags(String accountId, String delegateId) {
    Delegate delegate = delegateCache.get(accountId, delegateId, true);
    if (delegate == null) {
      throw new InvalidRequestException(
          format("Delegate with accountId: %s and delegateId: %s does not exists.", accountId, delegateId));
    }
    return DelegateDTO.convertToDTO(delegate, delegateSetupService.listDelegateImplicitSelectors(delegate));
  }

  @Override
  public DelegateDTO addDelegateTags(String accountId, String delegateId, DelegateTags delegateTags) {
    Delegate delegate = delegateCache.get(accountId, delegateId, true);
    if (delegate == null) {
      throw new InvalidRequestException(
          format("Delegate with accountId: %s and delegateId: %s does not exists.", accountId, delegateId));
    }
    List<String> existingTags = delegate.getTags();
    if (isNotEmpty(existingTags)) {
      existingTags.addAll(delegateTags.getTags());
      delegate.setTags(existingTags);
    } else {
      delegate.setTags(delegateTags.getTags());
    }
    Delegate updatedDelegate = updateTags(delegate);
    return DelegateDTO.convertToDTO(updatedDelegate, null);
  }

  @Override
  public DelegateDTO updateDelegateTags(String accountId, String delegateId, DelegateTags delegateTags) {
    Delegate delegate = delegateCache.get(accountId, delegateId, true);
    if (delegate == null) {
      throw new InvalidRequestException(
          format("Delegate with accountId: %s and delegateId: %s does not exists.", accountId, delegateId));
    }
    delegate.setTags(delegateTags.getTags());
    Delegate updatedDelegate = updateTags(delegate);
    return DelegateDTO.convertToDTO(updatedDelegate, null);
  }

  @Override
  public DelegateDTO deleteDelegateTags(String accountId, String delegateId) {
    Delegate delegate = delegateCache.get(accountId, delegateId, true);
    if (delegate == null) {
      throw new InvalidRequestException(
          format("Delegate with accountId: %s and delegateId: %s does not exists.", accountId, delegateId));
    }
    delegate.setTags(emptyList());
    Delegate updatedDelegate = updateTags(delegate);
    return DelegateDTO.convertToDTO(updatedDelegate, null);
  }

  private DelegateSequenceConfig generateNewSeqenceConfig(Delegate delegate, Integer seqNum) {
    log.info("Adding delegateSequenceConfig For delegate.hostname: {}, With SequenceNum: {}, for account:  {}",
        delegate.getHostName(), delegate.getSequenceNum(), delegate.getAccountId());

    DelegateSequenceConfig sequenceConfig = aDelegateSequenceBuilder()
                                                .withSequenceNum(seqNum)
                                                .withAccountId(delegate.getAccountId())
                                                .withHostName(delegate.getHostName())
                                                .withDelegateToken(delegate.getDelegateRandomToken())
                                                .withAppId(GLOBAL_APP_ID)
                                                .build();

    persistence.save(sequenceConfig);
    log.info("DelegateSequenceConfig saved: {}", sequenceConfig);

    return sequenceConfig;
  }

  private String getHostNameToBeUsedForECSDelegate(String hostName, String seqNum) {
    return hostName + DELIMITER + seqNum;
  }

  /**
   * Copy {SCOPE/TAG/PROFILE/KEYWORDS/DESCRIPTION } into new delegate
   */
  private void updateNewDelegateWithExistingDelegate(final Delegate delegate, final Delegate existingInactiveDelegate) {
    delegate.setExcludeScopes(existingInactiveDelegate.getExcludeScopes());
    delegate.setIncludeScopes(existingInactiveDelegate.getIncludeScopes());
    delegate.setDelegateProfileId(existingInactiveDelegate.getDelegateProfileId());
    delegate.setTags(existingInactiveDelegate.getTags());
    delegate.setKeywords(existingInactiveDelegate.getKeywords());
    delegate.setDescription(existingInactiveDelegate.getDescription());
  }

  private Delegate updateAllCgDelegatesInGroup(
      Delegate delegate, UpdateOperations<Delegate> updateOperations, String fieldBeingUpdate) {
    List<Delegate> retVal = new ArrayList<>();
    final List<Delegate> delegates = getAllDelegatesMatchingGroupName(delegate);

    if (isEmpty(delegates)) {
      return null;
    }

    for (Delegate delegateToBeUpdated : delegates) {
      try (AutoLogContext ignore = new DelegateLogContext(delegateToBeUpdated.getUuid(), OVERRIDE_NESTS)) {
        if ("SCOPES".equals(fieldBeingUpdate)) {
          log.info("Updating delegate scopes: includeScopes:{} excludeScopes:{}", delegate.getIncludeScopes(),
              delegate.getExcludeScopes());
        } else if ("TAGS".equals(fieldBeingUpdate)) {
          log.info("Updating delegate tags : tags:{}", delegate.getTags());
        } else {
          log.info("Updating grouped CG delegate {}", delegate.getDelegateName());
        }

        Delegate updatedDelegate = updateDelegate(delegateToBeUpdated, updateOperations);
        if (updatedDelegate.getUuid().equals(delegate.getUuid())) {
          retVal.add(updatedDelegate);
        }
      }
    }

    if (isNotEmpty(retVal)) {
      return retVal.get(0);
    } else {
      return null;
    }
  }

  /**
   * All delegates matching {AccId, HostName Prefix, Type = ECS}
   */
  private List<Delegate> getAllDelegatesMatchingGroupName(final Delegate delegate) {
    final Query<Delegate> filter = persistence.createQuery(Delegate.class, excludeAuthority)
                                       .filter(DelegateKeys.accountId, delegate.getAccountId())
                                       .filter(DelegateKeys.ng, false);
    final Query<Delegate> filter1 = filter.filter(DelegateKeys.delegateType, delegate.getDelegateType())
                                        .filter(DelegateKeys.delegateGroupName, delegate.getDelegateGroupName());
    return filter1.asList();
  }

  private boolean isValidSeqNum(String sequenceNum) {
    try {
      Integer.parseInt(sequenceNum);
    } catch (Exception e) {
      return false;
    }

    return true;
  }

  private boolean isDelegateWithoutPollingEnabled(Delegate delegate) {
    return !delegate.isPolllingModeEnabled();
  }

  private void updateWithTokenAndSeqNumIfEcsDelegate(Delegate delegate, Delegate savedDelegate) {
    if (ECS.equals(delegate.getDelegateType())) {
      savedDelegate.setDelegateRandomToken(delegate.getDelegateRandomToken());
      savedDelegate.setSequenceNum(delegate.getSequenceNum());
    }
  }

  @VisibleForTesting
  void updateExistingDelegateWithSequenceConfigData(Delegate delegate) {
    String hostName = getDelegateHostNameByRemovingSeqNum(delegate);
    String seqNum = getDelegateSeqNumFromHostName(delegate);
    DelegateSequenceConfig config =
        getDelegateSequenceConfig(delegate.getAccountId(), hostName, Integer.parseInt(seqNum));
    delegate.setDelegateRandomToken(config.getDelegateToken());
    delegate.setSequenceNum(String.valueOf(config.getSequenceNum()));
  }

  @VisibleForTesting
  String getDelegateHostNameByRemovingSeqNum(Delegate delegate) {
    return delegate.getHostName().substring(0, delegate.getHostName().lastIndexOf('_'));
  }

  @VisibleForTesting
  String getDelegateSeqNumFromHostName(Delegate delegate) {
    return delegate.getHostName().substring(delegate.getHostName().lastIndexOf('_') + 1);
  }

  private void destroyTheCurrentDelegate(
      String accountId, String delegateId, String delegateConnectionId, ConnectionMode connectionMode) {
    Delegate delegate = delegateCache.get(accountId, delegateId, false);
    delegateMetricsService.recordDelegateMetrics(delegate, DELEGATE_DESTROYED);
    switch (connectionMode) {
      case POLLING:
        log.warn("Sent self destruct command to delegate {}, with connectionId {}.", delegateId, delegateConnectionId);
        throw new DuplicateDelegateException(delegateId, delegateConnectionId);
      case STREAMING:
        broadcasterFactory.lookup(STREAM_DELEGATE + accountId, true)
            .broadcast(SELF_DESTRUCT + delegateId + "-" + delegateConnectionId);
        log.warn("Sent self destruct command to delegate {}, with connectionId {}.", delegateId, delegateConnectionId);
        break;
      default:
        throw new UnexpectedException("Non supported connection mode provided");
    }
  }
  //------ END: ECS Delegate Specific Methods

  //------ START: DelegateFeature Specific methods
  @Override
  public void deleteAllDelegatesExceptOne(String accountId, long shutdownInterval) {
    int retryCount = 0;
    while (true) {
      try {
        Optional<String> delegateToRetain = selectDelegateToRetain(accountId);

        if (delegateToRetain.isPresent()) {
          log.info("Deleting all delegates for account : {} except {}", accountId, delegateToRetain.get());

          retainOnlySelectedDelegatesAndDeleteRestByUuid(
              accountId, Collections.singletonList(delegateToRetain.get()), shutdownInterval);

          log.info("Deleted all delegates for account : {} except {}", accountId, delegateToRetain.get());
        } else {
          log.info("No delegate found to retain for account : {}", accountId);
        }

        break;
      } catch (Exception ex) {
        if (retryCount >= MAX_RETRIES) {
          log.error("Couldn't delete delegates for account: {}. Current Delegate Count : {}", accountId,
              getDelegates(accountId).size(), ex);
          break;
        }
        retryCount++;
      }
    }

    int numDelegates = getDelegates(accountId).size();
    if (numDelegates > delegatesFeature.getMaxUsageAllowedForAccount(accountId)) {
      sendEmailAboutDelegatesOverUsage(accountId, numDelegates);
    }
  }

  @Override
  public List<DelegateSizeDetails> fetchAvailableSizes() {
    try (InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("delegatesizes/sizes.json")) {
      String fileContent = IOUtils.toString(inputStream, UTF_8);
      AvailableDelegateSizes availableDelegateSizes = JsonUtils.asObject(fileContent, AvailableDelegateSizes.class);

      return availableDelegateSizes.getAvailableSizes();
    } catch (Exception e) {
      log.error("Unexpected exception occurred while trying read available delegate sizes from resource file.");
    }

    return null;
  }

  @Override
  public List<String> getConnectedDelegates(String accountId, List<String> delegateIds) {
    return delegateIds.stream()
        .filter(
            delegateId -> delegateConnectionDao.checkDelegateConnected(accountId, delegateId, getVersion(accountId)))
        .collect(toList());
  }

  private Optional<String> selectDelegateToRetain(String accountId) {
    return getDelegates(accountId)
        .stream()
        .max(Comparator.comparingLong(Delegate::getLastHeartBeat))
        .map(UuidAware::getUuid);
  }

  private List<Delegate> getDelegates(String accountId) {
    return list(PageRequestBuilder.aPageRequest().addFilter(DelegateKeys.accountId, Operator.EQ, accountId).build())
        .getResponse();
  }

  private void retainOnlySelectedDelegatesAndDeleteRestByUuid(
      String accountId, List<String> delegatesToRetain, long shutdownInterval) throws InterruptedException {
    Query<Delegate> query = persistence.createQuery(Delegate.class)
                                .filter(DelegateKeys.accountId, accountId)
                                .field(DelegateKeys.uuid)
                                .notIn(delegatesToRetain);

    UpdateOperations<Delegate> updateOps =
        persistence.createUpdateOperations(Delegate.class).set(DelegateKeys.status, DelegateInstanceStatus.DELETED);
    persistence.update(query, updateOps);

    // Waiting for shutdownInterval to ensure shutdown msg reach delegates before removing their entries from DB
    Thread.sleep(shutdownInterval);

    retainOnlySelectedDelegatesAndDeleteRest(accountId, delegatesToRetain);
  }

  private void sendEmailAboutDelegatesOverUsage(String accountId, int numDelegates) {
    Account account = accountService.get(accountId);
    String body = format(
        "Account is using more than [%d] delegates. Account Id : [%s], Company Name : [%s], Account Name : [%s], Delegate Count : [%d]",
        delegatesFeature.getMaxUsageAllowedForAccount(accountId), accountId, account.getCompanyName(),
        account.getAccountName(), numDelegates);
    String subjectMail =
        format("Found account with more than %d delegates", delegatesFeature.getMaxUsageAllowedForAccount(accountId));

    emailNotificationService.send(EmailData.builder()
                                      .hasHtml(false)
                                      .body(body)
                                      .subject(subjectMail)
                                      .to(Lists.newArrayList("support@harness.io"))
                                      .build());
  }
  //------ END: DelegateFeature Specific methods

  @VisibleForTesting
  protected DelegateInitializationDetails getDelegateInitializationDetails(String accountId, String delegateId) {
    Delegate delegate = delegateCache.get(accountId, delegateId, true);

    if (delegate.isProfileError()) {
      log.debug("Delegate {} could not be initialized correctly.", delegateId);
      return buildInitializationDetails(false, delegate);
    } else if (delegate.getProfileExecutedAt() > 0) {
      log.debug("Delegate {} was initialized correctly.", delegateId);
      return buildInitializationDetails(true, delegate);
    } else {
      DelegateProfile delegateProfile = delegateProfileService.get(accountId, delegate.getDelegateProfileId());

      if (isBlank(delegateProfile.getStartupScript())) {
        log.debug("Delegate {} was initialized correctly.", delegateId);
        return buildInitializationDetails(true, delegate);
      } else {
        log.debug("Delegate {} finalizing initialization correctly.", delegateId);
        return buildInitializationDetails(false, delegate);
      }
    }
  }

  private DelegateInitializationDetails buildInitializationDetails(boolean initialized, Delegate delegate) {
    return DelegateInitializationDetails.builder()
        .delegateId(delegate.getUuid())
        .hostname(delegate.getHostName())
        .initialized(initialized)
        .profileError(delegate.isProfileError())
        .profileExecutedAt(delegate.getProfileExecutedAt())
        .build();
  }

  @Override
  public String queueTask(DelegateTask task) {
    if (task.getUuid() == null) {
      task.setUuid(generateUuid());
    }
    log.debug("Task id [{}] has wait Id [{}], task Object: [{}]", task.getUuid(), task.getWaitId(), task);
    if (mainConfiguration.isDisableDelegateMgmtInManager()) {
      return delegateServiceClassicGrpcClient.queueTask(task);
    }
    return delegateTaskServiceClassic.queueTask(task);
  }

  @Override
  public void scheduleSyncTask(DelegateTask task) {
    delegateTaskServiceClassic.scheduleSyncTask(task);
  }

  @Override
  public <T extends DelegateResponseData> T executeTask(DelegateTask task) throws InterruptedException {
    if (task.getUuid() == null) {
      task.setUuid(generateUuid());
    }
    if (mainConfiguration.isDisableDelegateMgmtInManager()) {
      return delegateServiceClassicGrpcClient.executeTask(task);
    }
    return delegateTaskServiceClassic.executeTask(task);
  }

  @Override
  public boolean sampleDelegateExists(String accountId) {
    Key<Delegate> delegateKey = persistence.createQuery(Delegate.class)
                                    .filter(DelegateKeys.accountId, accountId)
                                    .filter(DelegateKeys.delegateName, SAMPLE_DELEGATE_NAME)
                                    .getKey();
    if (delegateKey == null) {
      return false;
    }
    return delegateConnectionDao.checkDelegateConnected(
        accountId, delegateKey.getId().toString(), versionInfoManager.getVersionInfo().getVersion());
  }

  @Override
  public List<Delegate> getNonDeletedDelegatesForAccount(String accountId) {
    return persistence.createQuery(Delegate.class)
        .filter(DelegateKeys.accountId, accountId)
        .field(DelegateKeys.status)
        .notEqual(DelegateInstanceStatus.DELETED)
        .asList();
  }

  @Override
  public boolean checkDelegateConnected(String accountId, String delegateId) {
    return delegateConnectionDao.checkDelegateConnected(accountId, delegateId, getVersion(accountId));
  }

  private String getVersion(String accountId) {
    String accountVersion = accountService.getAccountPrimaryDelegateVersion(accountId);
    accountVersion = Arrays.stream(accountVersion.split("-")).findFirst().get();
    return isNotEmpty(accountVersion) ? accountVersion : versionInfoManager.getVersionInfo().getVersion();
  }

  @Override
  public DelegateTask abortTask(String accountId, String delegateTaskId) {
    if (mainConfiguration.isDisableDelegateMgmtInManager()) {
      return delegateServiceClassicGrpcClient.abortTask(accountId, delegateTaskId);
    }
    return delegateTaskServiceClassic.abortTask(accountId, delegateTaskId);
  }

  @Override
  public String expireTask(String accountId, String delegateTaskId) {
    if (mainConfiguration.isDisableDelegateMgmtInManager()) {
      return delegateServiceClassicGrpcClient.expireTask(accountId, delegateTaskId);
    }
    return delegateTaskServiceClassic.expireTask(accountId, delegateTaskId);
  }

  public DelegateSizeDetails fetchDefaultDockerDelegateSize() {
    String fileName;
    if (DeployVariant.isCommunity(deployVersion)) {
      fileName = "delegatesizes/default_community_size.json";
    } else {
      fileName = "delegatesizes/default_size.json";
    }

    try (InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(fileName)) {
      String fileContent = IOUtils.toString(inputStream, UTF_8);
      return JsonUtils.asObject(fileContent, DelegateSizeDetails.class);

    } catch (Exception e) {
      log.error("Unexpected exception occurred while trying read default available delegate size from resource file.");
    }

    return null;
  }

  private boolean hasToken(DelegateSetupDetails delegateSetupDetails) {
    return delegateSetupDetails != null && isNotBlank(delegateSetupDetails.getTokenName());
  }

  public void validateDockerDelegateSetupDetails(
      String accountId, DelegateSetupDetails delegateSetupDetails, String delegateType) {
    if (delegateSetupDetails == null) {
      throw new InvalidRequestException("Delegate Setup Details must be provided.");
    }

    if (!DOCKER.equals(delegateType)) {
      throw new InvalidRequestException(String.format("Delegate type must be %s.", delegateType));
    }

    if (isBlank(delegateSetupDetails.getName())) {
      throw new InvalidRequestException("Delegate Name must be provided.");
    }
    checkUniquenessOfDelegateName(accountId, delegateSetupDetails.getName(), true);

    if (isEmpty(delegateSetupDetails.getTokenName())) {
      throw new InvalidRequestException("Delegate Token must be provided.", USER);
    }
  }

  @Override
  public File downloadNgDocker(String managerHost, String verificationServiceUrl, String accountId,
      DelegateSetupDetails delegateSetupDetails) throws IOException {
    validateDockerDelegateSetupDetails(accountId, delegateSetupDetails, DOCKER);

    File composeYaml = File.createTempFile(HARNESS_NG_DELEGATE + "-docker-compose", YAML);

    ImmutableMap<String, String> scriptParams = getDockerScriptParametersForTemplate(
        managerHost, verificationServiceUrl, accountId, delegateSetupDetails.getName(), delegateSetupDetails);

    saveProcessedTemplate(scriptParams, composeYaml, HARNESS_NG_DELEGATE + "-docker-compose.yaml.ftl");
    delegateTelemetryPublisher.sendTelemetryTrackEvents(accountId, DOCKER, true, DELEGATE_CREATED_EVENT);
    return composeYaml;
  }

  @Override
  public String createDelegateGroup(String accountId, DelegateSetupDetails delegateSetupDetails) {
    if (delegateSetupDetails != null && delegateSetupDetails.getDelegateType().equals(DOCKER)) {
      validateDockerDelegateSetupDetails(accountId, delegateSetupDetails, DOCKER);
    } else {
      validateKubernetesSetupDetails(accountId, delegateSetupDetails);
    }
    DelegateGroup delegateGroup = upsertDelegateGroup(delegateSetupDetails.getName(), accountId, delegateSetupDetails);
    sendNewDelegateGroupAuditEvent(delegateSetupDetails, delegateGroup, accountId);
    return delegateGroup.getUuid();
  }

  @Override
  public File generateKubernetesYaml(String accountId, DelegateSetupDetails delegateSetupDetails, String managerHost,
      String verificationServiceUrl, MediaType fileFormat) throws IOException {
    validateKubernetesSetupDetails(accountId, delegateSetupDetails);
    File kubernetesDelegateFile = File.createTempFile(KUBERNETES_DELEGATE, ".tar");

    try (TarArchiveOutputStream out = new TarArchiveOutputStream(new FileOutputStream(kubernetesDelegateFile))) {
      out.putArchiveEntry(new TarArchiveEntry(KUBERNETES_DELEGATE + "/"));
      out.closeArchiveEntry();

      String version;
      if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES) {
        List<String> delegateVersions = accountService.getDelegateConfiguration(accountId).getDelegateVersions();
        version = delegateVersions.get(delegateVersions.size() - 1);
      } else {
        version = EMPTY_VERSION;
      }

      boolean isCiEnabled = accountService.isNextGenEnabled(accountId);

      DelegateSizeDetails sizeDetails = fetchAvailableSizes()
                                            .stream()
                                            .filter(size -> size.getSize() == delegateSetupDetails.getSize())
                                            .findFirst()
                                            .orElse(null);

      // TODO: ARPIT remove creating delegate group here after UI starts using the createDelegteGroup method
      DelegateGroup delegateGroup =
          upsertDelegateGroup(delegateSetupDetails.getName(), accountId, delegateSetupDetails);
      sendNewDelegateGroupAuditEvent(delegateSetupDetails, delegateGroup, accountId);

      ImmutableMap<String, String> scriptParams = getJarAndScriptRunTimeParamMap(
          this.finalizeTemplateParametersWithMtlsIfRequired(
              TemplateParameters.builder()
                  .accountId(accountId)
                  .version(version)
                  .managerHost(managerHost)
                  .verificationHost(verificationServiceUrl)
                  .delegateName(delegateSetupDetails.getName())
                  .delegateType(KUBERNETES)
                  .ciEnabled(isCiEnabled)
                  .delegateDescription(delegateSetupDetails.getDescription())
                  .delegateSize(sizeDetails.getSize().name())
                  .delegateReplicas(sizeDetails.getReplicas())
                  .delegateRam(sizeDetails.getRam() / sizeDetails.getReplicas())
                  .delegateCpu(sizeDetails.getCpu() / sizeDetails.getReplicas())
                  .delegateRequestsRam(sizeDetails.getRam() / sizeDetails.getReplicas())
                  .delegateRequestsCpu(sizeDetails.getCpu() / sizeDetails.getReplicas())
                  .delegateTags(isNotEmpty(delegateSetupDetails.getTags())
                          ? String.join(",", delegateSetupDetails.getTags())
                          : "")
                  .delegateNamespace(delegateSetupDetails.getK8sConfigDetails().getNamespace())
                  .k8sPermissionsType(delegateSetupDetails.getK8sConfigDetails().getK8sPermissionType())
                  .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
                  .delegateTokenName(delegateSetupDetails.getTokenName())),
          true);

      File yaml = File.createTempFile(HARNESS_DELEGATE, YAML);
      String templateName = obtainK8sTemplateNameFromConfig(delegateSetupDetails.getK8sConfigDetails(), accountId);
      saveProcessedTemplate(scriptParams, yaml, templateName);
      yaml = new File(yaml.getAbsolutePath());

      delegateTelemetryPublisher.sendTelemetryTrackEvents(accountId, KUBERNETES, true, DELEGATE_CREATED_EVENT);

      if (fileFormat != null && fileFormat.equals(MediaType.TEXT_PLAIN_TYPE)) {
        return yaml;
      }

      TarArchiveEntry yamlTarArchiveEntry =
          new TarArchiveEntry(yaml, KUBERNETES_DELEGATE + "/" + HARNESS_DELEGATE + YAML);
      out.putArchiveEntry(yamlTarArchiveEntry);
      try (FileInputStream fis = new FileInputStream(yaml)) {
        IOUtils.copy(fis, out);
      }
      out.closeArchiveEntry();

      addReadmeFile(out);

      out.flush();
      out.finish();
    }

    File gzipKubernetesDelegateFile = File.createTempFile(DELEGATE_DIR, TAR_GZ);
    compressGzipFile(kubernetesDelegateFile, gzipKubernetesDelegateFile);
    return gzipKubernetesDelegateFile;
  }

  @Override
  public File generateNgHelmValuesYaml(String accountId, DelegateSetupDetails delegateSetupDetails, String managerHost,
      String verificationServiceUrl) throws IOException {
    validateKubernetesSetupDetails(accountId, delegateSetupDetails);

    String version;
    if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES) {
      List<String> delegateVersions = accountService.getDelegateConfiguration(accountId).getDelegateVersions();
      version = delegateVersions.get(delegateVersions.size() - 1);
    } else {
      version = EMPTY_VERSION;
    }

    boolean isCiEnabled = accountService.isNextGenEnabled(accountId);

    DelegateSizeDetails sizeDetails = fetchAvailableSizes()
                                          .stream()
                                          .filter(size -> size.getSize() == delegateSetupDetails.getSize())
                                          .findFirst()
                                          .orElse(null);

    // TODO: ARPIT remove creating delegate group here after UI starts using the createDelegteGroup method
    DelegateGroup delegateGroup = upsertDelegateGroup(delegateSetupDetails.getName(), accountId, delegateSetupDetails);
    sendNewDelegateGroupAuditEvent(delegateSetupDetails, delegateGroup, accountId);

    ImmutableMap<String, String> scriptParams = getJarAndScriptRunTimeParamMap(
        TemplateParameters.builder()
            .accountId(accountId)
            .version(version)
            .managerHost(managerHost)
            .verificationHost(verificationServiceUrl)
            .delegateName(delegateSetupDetails.getName())
            .delegateType(HELM_DELEGATE)
            .ciEnabled(isCiEnabled)
            .delegateDescription(delegateSetupDetails.getDescription())
            .delegateSize(sizeDetails.getSize().name())
            .delegateReplicas(sizeDetails.getReplicas())
            .delegateRam(sizeDetails.getRam() / sizeDetails.getReplicas())
            .delegateCpu(sizeDetails.getCpu() / sizeDetails.getReplicas())
            .delegateRequestsRam(sizeDetails.getRam() / sizeDetails.getReplicas())
            .delegateRequestsCpu(sizeDetails.getCpu() / sizeDetails.getReplicas())
            .delegateTags(
                isNotEmpty(delegateSetupDetails.getTags()) ? String.join(",", delegateSetupDetails.getTags()) : "")
            .delegateNamespace(delegateSetupDetails.getK8sConfigDetails().getNamespace())
            .k8sPermissionsType(delegateSetupDetails.getK8sConfigDetails().getK8sPermissionType())
            .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
            .delegateTokenName(delegateSetupDetails.getTokenName())
            .build(),
        true);

    File yaml = File.createTempFile(HARNESS_NG_DELEGATE, YAML);
    saveProcessedTemplate(scriptParams, yaml, "delegate-ng-helm-values.yaml.ftl");
    delegateTelemetryPublisher.sendTelemetryTrackEvents(accountId, HELM_DELEGATE, true, DELEGATE_CREATED_EVENT);
    return yaml;
  }

  private ImmutableMap<String, String> getDockerScriptParametersForTemplate(String managerHost,
      String verificationServiceUrl, String accountId, String delegateName, DelegateSetupDetails delegateSetupDetails) {
    String version;
    if (mainConfiguration.getDeployMode() == DeployMode.KUBERNETES) {
      List<String> delegateVersions = accountService.getDelegateConfiguration(accountId).getDelegateVersions();
      version = delegateVersions.get(delegateVersions.size() - 1);
    } else {
      version = EMPTY_VERSION;
    }

    DelegateSizeDetails sizeDetails = getDelegateSizeDetails(delegateSetupDetails);
    TemplateParameters templateParameters =
        TemplateParameters.builder()
            .delegateTags(delegateSetupDetails != null && isNotEmpty(delegateSetupDetails.getTags())
                    ? String.join(",", delegateSetupDetails.getTags())
                    : "")
            .delegateDescription(delegateSetupDetails != null ? delegateSetupDetails.getDescription() : null)
            .delegateNamespace(delegateSetupDetails != null && delegateSetupDetails.getK8sConfigDetails() != null
                    ? delegateSetupDetails.getK8sConfigDetails().getNamespace()
                    : EMPTY)
            .delegateXmx(String.valueOf(sizeDetails.getRam()))
            .delegateCpu(sizeDetails.getCpu())
            .accountId(accountId)
            .delegateType(DOCKER)
            .version(version)
            .managerHost(managerHost)
            .verificationHost(verificationServiceUrl)
            .delegateName(delegateName)
            .logStreamingServiceBaseUrl(mainConfiguration.getLogStreamingServiceConfig().getBaseUrl())
            .ceEnabled(false)
            .delegateTokenName(delegateSetupDetails != null ? delegateSetupDetails.getTokenName() : null)
            .build();

    ImmutableMap<String, String> paramMap = getJarAndScriptRunTimeParamMap(templateParameters, true);

    if (isEmpty(paramMap)) {
      throw new InvalidArgumentsException(Pair.of("scriptParams", "Failed to get jar and script runtime params."));
    }

    return paramMap;
  }

  private DelegateSizeDetails getDelegateSizeDetails(DelegateSetupDetails delegateSetupDetails) {
    return fetchAvailableSizes()
        .stream()
        .filter(size -> delegateSetupDetails != null && (size.getSize() == delegateSetupDetails.getSize()))
        .findFirst()
        .orElse(fetchDefaultDockerDelegateSize());
  }

  @Override
  public void checkUniquenessOfDelegateName(String accountId, String delegateName, boolean isNg) {
    if (isNg) {
      Query<DelegateGroup> delegateGroupQuery = persistence.createQuery(DelegateGroup.class)
                                                    .filter(DelegateGroupKeys.accountId, accountId)
                                                    .filter(DelegateGroupKeys.name, delegateName)
                                                    .filter(DelegateGroupKeys.ng, true);
      if (delegateGroupQuery.get() != null) {
        throw new InvalidRequestException(
            "Delegate with same name exists. Delegate name must be unique across account.", USER);
      }
    }

    Query<Delegate> delegateQuery = persistence.createQuery(Delegate.class)
                                        .filter(DelegateKeys.accountId, accountId)
                                        .filter(DelegateKeys.delegateName, delegateName)
                                        .field(DelegateKeys.status)
                                        .notEqual(DelegateInstanceStatus.DELETED);
    if (delegateQuery.get() != null) {
      throw new InvalidRequestException(
          "Delegate with same name exists. Delegate name must be unique across account.", USER);
    }
  }

  @Override
  public void markDelegatesAsDeletedOnDeletingOwner(String accountId, DelegateEntityOwner owner) {
    Query<Delegate> query = persistence.createQuery(Delegate.class)
                                .filter(DelegateKeys.accountId, accountId)
                                .filter(DelegateKeys.owner, owner);

    UpdateOperations<Delegate> updateOperations =
        persistence.createUpdateOperations(Delegate.class).set(DelegateKeys.status, DelegateInstanceStatus.DELETED);

    persistence.update(query, updateOperations);
  }

  @Override
  public List<DelegateDTO> listDelegatesHavingTags(String accountId, DelegateTags tags) {
    List<Delegate> delegateList =
        persistence.createQuery(Delegate.class).filter(DelegateKeys.accountId, accountId).asList();
    return delegateList.stream()
        .filter(delegate -> checkForDelegateHavingAllTags(delegate, tags))
        .map(delegate
            -> DelegateDTO.convertToDTO(delegate, delegateSetupService.listDelegateImplicitSelectors(delegate)))
        .collect(Collectors.toList());
  }

  private boolean checkForDelegateHavingAllTags(Delegate delegate, DelegateTags tags) {
    List<String> delegateTags = delegateSetupService.listDelegateImplicitSelectors(delegate);
    if (isNotEmpty(delegate.getTags())) {
      delegateTags.addAll(delegate.getTags());
    }
    return delegateTags.containsAll(tags.getTags());
  }

  private String getDelegateXmx(String delegateType) {
    // TODO: ARPIT remove this community and null check once new delegate and watcher goes in prod.
    return (DeployVariant.isCommunity(deployVersion) || (delegateType != null && (delegateType.equals(DOCKER))))
        ? "-Xmx512m"
        : "-Xmx1536m";
  }

  private boolean matchOwners(DelegateEntityOwner existingOwner, DelegateEntityOwner owner) {
    if ((existingOwner == null && owner != null) || (existingOwner != null && owner == null)) {
      return false;
    } else if (existingOwner == null && owner == null) {
      return true;
    }
    return existingOwner.getIdentifier().equals(owner.getIdentifier());
  }

  private void sendUnregisterDelegateAuditEvent(Delegate delegate, String accountId) {
    if (delegate.isNg()) {
      String orgIdentifier = delegate.getOwner() != null
          ? DelegateEntityOwnerHelper.extractOrgIdFromOwnerIdentifier(delegate.getOwner().getIdentifier())
          : null;

      String projectIdentifier = delegate.getOwner() != null
          ? DelegateEntityOwnerHelper.extractProjectIdFromOwnerIdentifier(delegate.getOwner().getIdentifier())
          : null;
      outboxService.save(DelegateUnregisterEvent.builder()
                             .accountIdentifier(accountId)
                             .orgIdentifier(orgIdentifier)
                             .projectIdentifier(projectIdentifier)
                             .delegateSetupDetails(DelegateSetupDetails.builder().name(delegate.getHostName()).build())
                             .build());
    } else {
      auditServiceHelper.reportDeleteForAuditingUsingAccountId(accountId, delegate);
    }
  }

  private void sendRegisterDelegateAuditEvent(
      Delegate delegate, DelegateSetupDetails delegateSetupDetails, String accountId) {
    if (delegate.isNg()) {
      if (delegateSetupDetails != null) {
        outboxService.save(DelegateRegisterEvent.builder()
                               .accountIdentifier(accountId)
                               .orgIdentifier(delegateSetupDetails.getOrgIdentifier())
                               .projectIdentifier(delegateSetupDetails.getProjectIdentifier())
                               .delegateSetupDetails(delegateSetupDetails)
                               .build());
      } else {
        log.info("DelegateSetupDetails empty while DelegateAuditEvent");
      }
    } else {
      auditServiceHelper.reportForAuditingUsingAccountId(
          delegate.getAccountId(), delegate, delegate, Type.DELEGATE_REGISTRATION);
    }
  }

  private void sendDelegateDeleteAuditEvent(Delegate delegate, String accountId) {
    if (delegate.isNg()) {
      String orgIdentifier = delegate.getOwner() != null
          ? DelegateEntityOwnerHelper.extractOrgIdFromOwnerIdentifier(delegate.getOwner().getIdentifier())
          : null;

      String projectIdentifier = delegate.getOwner() != null
          ? DelegateEntityOwnerHelper.extractProjectIdFromOwnerIdentifier(delegate.getOwner().getIdentifier())
          : null;
      outboxService.save(DelegateUnregisterEvent.builder()
                             .accountIdentifier(accountId)
                             .orgIdentifier(orgIdentifier)
                             .projectIdentifier(projectIdentifier)
                             .delegateSetupDetails(DelegateSetupDetails.builder().name(delegate.getHostName()).build())
                             .build());
    } else {
      auditServiceHelper.reportDeleteForAuditingUsingAccountId(accountId, delegate);
    }
  }

  private void sendNewDelegateGroupAuditEvent(
      DelegateSetupDetails delegateSetupDetails, DelegateGroup delegateGroup, String accountId) {
    if (delegateGroup.isNg()) {
      outboxService.save(
          DelegateGroupUpsertEvent.builder()
              .accountIdentifier(accountId)
              .orgIdentifier(delegateSetupDetails != null ? delegateSetupDetails.getOrgIdentifier() : null)
              .projectIdentifier(delegateSetupDetails != null ? delegateSetupDetails.getProjectIdentifier() : null)
              .delegateGroupId(delegateGroup.getUuid())
              .delegateSetupDetails(delegateSetupDetails)
              .build());
    } else {
      if (delegateGroup != null) {
        auditServiceHelper.reportForAuditingUsingAccountId(
            delegateGroup.getAccountId(), delegateGroup, delegateGroup, Type.DELEGATE_GROUP);
      }
    }
  }
  private Optional<String> getDelegateTokenNameFromGlobalContext() {
    DelegateTokenGlobalContextData delegateTokenGlobalContextData =
        GlobalContextManager.get(DelegateTokenGlobalContextData.TOKEN_NAME);
    if (delegateTokenGlobalContextData != null) {
      return Optional.ofNullable(delegateTokenGlobalContextData.getTokenName());
    }
    log.warn("Delegate token name not found in Global Context Data. Please verify manually.");
    return Optional.empty();
  }

  private Optional<String> getOrgIdentifierUsingTokenFromGlobalContext(String accountId) {
    Optional<String> delegateTokenName = getDelegateTokenNameFromGlobalContext();
    if (delegateTokenName.isPresent()) {
      DelegateTokenDetails delegateTokenDetails =
          delegateNgTokenService.getDelegateToken(accountId, delegateTokenName.get());
      return delegateTokenDetails != null ? Optional.ofNullable(
                 DelegateEntityOwnerHelper.extractOrgIdFromOwnerIdentifier(delegateTokenDetails.getOwnerIdentifier()))
                                          : Optional.empty();
    }
    return Optional.empty();
  }

  private Optional<String> getProjectIdentifierUsingTokenFromGlobalContext(String accountId) {
    Optional<String> delegateTokenName = getDelegateTokenNameFromGlobalContext();
    if (delegateTokenName.isPresent()) {
      DelegateTokenDetails delegateTokenDetails =
          delegateNgTokenService.getDelegateToken(accountId, delegateTokenName.get());
      return delegateTokenDetails != null
          ? Optional.ofNullable(
              DelegateEntityOwnerHelper.extractProjectIdFromOwnerIdentifier(delegateTokenDetails.getOwnerIdentifier()))
          : Optional.empty();
    }
    return Optional.empty();
  }
}
