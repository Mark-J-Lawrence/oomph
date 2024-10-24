/*
 * Copyright (c) 2014-2017 Eike Stepper (Loehne, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 *    Ericsson AB (Julian Enoch) - Bug 425815 - Add support for secure context variables
 *    Ericsson AB (Julian Enoch) - Bug 434512 - Disable prompt for master password recovery information
 */
package org.eclipse.oomph.setup.internal.core;

import org.eclipse.oomph.base.Annotation;
import org.eclipse.oomph.internal.setup.SetupPrompter;
import org.eclipse.oomph.internal.setup.SetupProperties;
import org.eclipse.oomph.p2.core.Agent;
import org.eclipse.oomph.p2.core.P2Util;
import org.eclipse.oomph.p2.core.Profile;
import org.eclipse.oomph.setup.AnnotationConstants;
import org.eclipse.oomph.setup.Installation;
import org.eclipse.oomph.setup.ProductVersion;
import org.eclipse.oomph.setup.Scope;
import org.eclipse.oomph.setup.SetupTaskContext;
import org.eclipse.oomph.setup.Trigger;
import org.eclipse.oomph.setup.User;
import org.eclipse.oomph.setup.Workspace;
import org.eclipse.oomph.setup.impl.InstallationTaskImpl;
import org.eclipse.oomph.setup.util.StringExpander;
import org.eclipse.oomph.util.IOUtil;
import org.eclipse.oomph.util.OS;
import org.eclipse.oomph.util.OfflineMode;
import org.eclipse.oomph.util.StringUtil;

import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.URIConverter;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.equinox.internal.p2.metadata.InstallableUnit;
import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.ITouchpointData;
import org.eclipse.equinox.p2.metadata.ITouchpointInstruction;
import org.eclipse.equinox.p2.metadata.expression.IMatchExpression;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.osgi.util.NLS;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Eike Stepper
 */
public abstract class AbstractSetupTaskContext extends StringExpander implements SetupTaskContext
{
  private static final Pattern FILTER_OVERRIDE_PATTERN = Pattern.compile(".*\\.([^.]+)\\.filter\\.override\\.pattern"); //$NON-NLS-1$

  private static final Pattern setLauncherNamePattern = Pattern.compile("setLauncherName\\(name:([^)]*)\\)"); //$NON-NLS-1$

  private SetupPrompter prompter;

  private Trigger trigger;

  private SetupContext setupContext;

  private Boolean selfHosting;

  private boolean performing;

  private boolean mirrors = true;

  private Set<String> restartReasons = new LinkedHashSet<>();

  private URIConverter uriConverter;

  private Map<Object, Object> map = new LinkedHashMap<>();

  private final Map<String, Map<Pattern, String>> filterOverridePatternReplacements = new LinkedHashMap<>();

  private String launcherName;

  private InstallableUnit filterContextIU;

  private final Map<String, String> filterContentCache = new HashMap<>();

  protected AbstractSetupTaskContext(URIConverter uriConverter, SetupPrompter prompter, Trigger trigger, SetupContext setupContext)
  {
    this.uriConverter = uriConverter;
    this.prompter = prompter;
    this.trigger = trigger;

    initialize(setupContext);
  }

  private void initialize(SetupContext setupContext)
  {
    setSetupContext(setupContext);

    Map<String, String> filterContext = new LinkedHashMap<>();
    Map<String, String> env = System.getenv();
    synchronized (env)
    {
      for (Map.Entry<String, String> entry : env.entrySet())
      {
        String key = entry.getKey();
        String value = entry.getValue();
        put(key, value);
        filterContext.put(key.replace('_', '.').toLowerCase(), value);
      }
    }

    Properties properties = System.getProperties();
    synchronized (properties)
    {
      for (Map.Entry<Object, Object> entry : properties.entrySet())
      {
        Object key = entry.getKey();
        Object value = entry.getValue();
        put(key, value);

        if ("eclipse.home.location".equals(key)) //$NON-NLS-1$
        {
          URI eclipseHomeRootLocation = URI.createURI(value.toString()).trimSegments(OS.INSTANCE.isMac() ? 3 : 1);
          put("eclipse.home.root.location", eclipseHomeRootLocation.toString()); //$NON-NLS-1$
        }

        if (key instanceof String && value instanceof String)
        {
          filterContext.put(((String)key).toLowerCase(), (String)value);
        }
      }
    }

    String instanceArea = filterContext.get(Location.INSTANCE_AREA_TYPE);
    if (instanceArea != null && !instanceArea.equals(filterContext.get(Location.CONFIGURATION_AREA_TYPE)))
    {
      URI uri = URI.createURI(instanceArea);
      if (uri.isFile() && uri.scheme() != null)
      {
        if (uri.hasTrailingPathSeparator())
        {
          uri = uri.trimSegments(1);
        }

        String workspaceLocation = uri.toFileString();
        put("workspace.location", workspaceLocation); //$NON-NLS-1$
        filterContext.put("workspace.location", workspaceLocation); //$NON-NLS-1$
      }
    }

    OS os = getOS();
    put("osgi.ws", os.getOsgiWS()); //$NON-NLS-1$
    put("osgi.os", os.getOsgiOS()); //$NON-NLS-1$
    put("osgi.arch", os.getOsgiArch()); //$NON-NLS-1$
    filterContext.put("osgi.ws", os.getOsgiWS()); //$NON-NLS-1$
    filterContext.put("osgi.os", os.getOsgiOS()); //$NON-NLS-1$
    filterContext.put("osgi.arch", os.getOsgiArch()); //$NON-NLS-1$

    for (Map.Entry<String, String> entry : SetupCorePlugin.INSTANCE.getImplicitInstallationVariables().entrySet())
    {
      String key = entry.getKey();
      String value = entry.getValue();
      filterContext.put(key, value);
      put(key, value);
    }

    for (Map.Entry<String, String> entry : SetupCorePlugin.INSTANCE.getImplicitWorkspaceVariables().entrySet())
    {
      String key = entry.getKey();
      String value = entry.getValue();
      filterContext.put(key, value);
      put(key, value);
    }

    filterContextIU = (InstallableUnit)InstallableUnit.contextIU(filterContext);

    // Do this late because \ is replaced by / when looking at this property.
    put(SetupProperties.PROP_UPDATE_URL, SetupCorePlugin.UPDATE_URL);

    for (Map.Entry<String, String> entry : CONTROL_CHARACTER_VALUES.entrySet())
    {
      put(entry.getKey(), entry.getValue());
    }
  }

  public Map<Object, Object> getMap()
  {
    return map;
  }

  @Override
  public SetupPrompter getPrompter()
  {
    return prompter;
  }

  public void setPrompter(SetupPrompter prompter)
  {
    this.prompter = prompter;
  }

  @Override
  public Trigger getTrigger()
  {
    return trigger;
  }

  @Override
  public void checkCancelation()
  {
    if (isCanceled())
    {
      throw new OperationCanceledException();
    }
  }

  @Override
  public boolean isOffline()
  {
    return OfflineMode.isEnabled();
  }

  public void setOffline(boolean offline)
  {
    // Make sure to change this plugin (so that the build qualifier is incremented) when the return type of OfflineMode.setEnabled() changes.
    OfflineMode.setEnabled(offline);
  }

  @Override
  public boolean isMirrors()
  {
    return mirrors;
  }

  public void setMirrors(boolean mirrors)
  {
    this.mirrors = mirrors;
  }

  @Override
  public boolean isSelfHosting()
  {
    if (selfHosting == null)
    {
      try
      {
        Agent agent = P2Util.getAgentManager().getCurrentAgent();
        if (agent != null)
        {
          Profile profile = agent.getCurrentProfile();
          selfHosting = profile == null || profile.isSelfHosting();
        }
        else
        {
          selfHosting = true;
        }
      }
      catch (Throwable ex)
      {
        selfHosting = true;
      }
    }

    return selfHosting;
  }

  @Override
  public boolean isPerforming()
  {
    return performing;
  }

  @Override
  public boolean isRestartNeeded()
  {
    return !restartReasons.isEmpty();
  }

  @Override
  public void setRestartNeeded(String reason)
  {
    restartReasons.add(reason);
  }

  public Set<String> getRestartReasons()
  {
    return restartReasons;
  }

  @Override
  public URI redirect(URI uri)
  {
    if (uri == null)
    {
      return null;
    }

    return getURIConverter().normalize(uri);
  }

  @Override
  public String redirect(String uri)
  {
    if (!StringUtil.isEmpty(uri))
    {
      try
      {
        return redirect(URI.createURI(uri)).toString();
      }
      catch (RuntimeException ex)
      {
        // Ignore.
      }
    }

    return uri;
  }

  @Override
  public URIConverter getURIConverter()
  {
    return uriConverter;
  }

  @Override
  public OS getOS()
  {
    return getPrompter().getOS();
  }

  @Override
  public boolean matchesFilterContext(String filter)
  {
    if (StringUtil.isEmpty(filter))
    {
      return true;
    }

    try
    {
      IMatchExpression<IInstallableUnit> matchExpression = InstallableUnit.parseFilter(filter);
      return matchExpression.isMatch(filterContextIU);
    }
    catch (RuntimeException ex)
    {
      // If the filter can't be parsed, assume it matches nothing.
      return false;
    }
  }

  protected void putFilterProperty(String key, String value)
  {
    filterContextIU.setProperty(key, value);
  }

  @Override
  public File getProductLocation()
  {
    File installationLocation = getInstallationLocation();
    if (installationLocation == null)
    {
      return null;
    }

    String relativeProductFolder = getRelativeProductFolder();
    return new File(installationLocation, relativeProductFolder);
  }

  @Override
  public File getProductConfigurationLocation()
  {
    File productLocation = getProductLocation();
    if (productLocation == null)
    {
      return null;
    }

    return new File(productLocation, InstallationTaskImpl.CONFIGURATION_FOLDER_NAME);
  }

  @Override
  public String getRelativeProductFolder()
  {
    String productFolderName = getProductFolderName();
    return getOS().getRelativeProductFolder(productFolderName);
  }

  private String getProductFolderName()
  {
    Installation installation = getInstallation();
    ProductVersion productVersion = installation.getProductVersion();

    OS os = getOS();
    return getProductFolderName(productVersion, os);
  }

  public static String getProductFolderName(ProductVersion productVersion, OS os)
  {
    String osgiOS = os.getOsgiOS();
    String osgiWS = os.getOsgiWS();
    String osgiArch = os.getOsgiArch();

    String[] keys = new String[] { //
        AnnotationConstants.KEY_FOLDER_NAME + '.' + osgiOS + '.' + osgiWS + '.' + osgiArch, //
        AnnotationConstants.KEY_FOLDER_NAME + '.' + osgiOS + '.' + osgiWS, //
        AnnotationConstants.KEY_FOLDER_NAME + '.' + osgiOS, //
        AnnotationConstants.KEY_FOLDER_NAME, //
    };

    return getProductFolderName(productVersion, keys);
  }

  private static String getProductFolderName(Scope scope, String[] keys)
  {
    if (scope == null)
    {
      return ""; //$NON-NLS-1$
    }

    Annotation annotation = scope.getAnnotation(AnnotationConstants.ANNOTATION_BRANDING_INFO);
    if (annotation != null)
    {
      EMap<String, String> details = annotation.getDetails();

      for (String key : keys)
      {
        String folderName = details.get(key);
        if (folderName != null)
        {
          return folderName;
        }
      }
    }

    return getProductFolderName(scope.getParentScope(), keys);
  }

  @Override
  public String getLauncherName()
  {
    if (launcherName == null)
    {
      IProfile profile = getProfile();
      launcherName = getLauncherName(profile);
    }

    return launcherName;
  }

  private static String getLauncherName(IProfile profile)
  {
    for (IInstallableUnit iu : P2Util.asIterable(profile.query(QueryUtil.createIUAnyQuery(), null)))
    {
      Collection<ITouchpointData> touchpointDatas = iu.getTouchpointData();
      if (touchpointDatas != null)
      {
        for (ITouchpointData touchpointData : touchpointDatas)
        {
          ITouchpointInstruction instruction = touchpointData.getInstruction("configure"); //$NON-NLS-1$
          if (instruction != null)
          {
            String body = instruction.getBody();
            if (body != null)
            {
              Matcher matcher = setLauncherNamePattern.matcher(body);
              if (matcher.matches())
              {
                return matcher.group(1);
              }
            }
          }
        }
      }
    }

    SetupCorePlugin.INSTANCE.log(NLS.bind(Messages.AbstractSetupTaskContext_NoLauncherName_message, profile.getProfileId()), IStatus.WARNING);

    return "eclipse"; //$NON-NLS-1$
  }

  public Profile getProfile()
  {
    Profile profile = (Profile)get(Profile.class);
    if (profile == null)
    {
      profile = P2Util.getAgentManager().getCurrentAgent().getCurrentProfile();
    }

    return profile;
  }

  @Override
  public Workspace getWorkspace()
  {
    return setupContext.getWorkspace();
  }

  public SetupContext getSetupContext()
  {
    return setupContext;
  }

  protected final void setSetupContext(SetupContext setupContext)
  {
    this.setupContext = setupContext;
  }

  @Override
  public User getUser()
  {
    return setupContext.getUser();
  }

  @Override
  public Installation getInstallation()
  {
    return setupContext.getInstallation();
  }

  protected final void setPerforming(boolean performing)
  {
    this.performing = performing;
  }

  @Override
  public Object get(Object key)
  {
    Object value = map.get(key);
    if (value == null && key instanceof String)
    {
      String name = (String)key;
      if (name.indexOf('.') != -1)
      {
        name = name.replace('.', '_');
        value = map.get(name);
      }
    }

    return value;
  }

  @Override
  public Object put(Object key, Object value)
  {
    if (key instanceof String && value instanceof String)
    {
      handleFilterOverridePattern(key.toString(), value.toString());
    }

    return map.put(key, value);
  }

  private void handleFilterOverridePattern(String key, String value)
  {
    Matcher matcher = FILTER_OVERRIDE_PATTERN.matcher(key);
    if (matcher.matches())
    {
      String filterName = matcher.group(1);
      String[] pair = value.split("->"); //$NON-NLS-1$
      if (pair.length == 2)
      {
        try
        {
          filterOverridePatternReplacements.computeIfAbsent(filterName, it -> new LinkedHashMap<>()).put(Pattern.compile(pair[0]), pair[1]);
        }
        catch (RuntimeException ex)
        {
          SetupCorePlugin.INSTANCE.log(new RuntimeException(NLS.bind(Messages.AbstractSetupTaskContext_FilterOverridePatternProblem_message, key, value), ex));
        }
      }
    }
  }

  @Override
  public Set<Object> keySet()
  {
    return map.keySet();
  }

  protected String lookup(String key)
  {
    Object object = get(key);
    if (object != null)
    {
      return object.toString();
    }

    return null;
  }

  @Override
  protected String filter(String value, String filterName)
  {
    Map<Pattern, String> replacements = filterOverridePatternReplacements.get(filterName);
    if (replacements != null)
    {
      for (Map.Entry<Pattern, String> entry : replacements.entrySet())
      {
        Matcher matcher = entry.getKey().matcher(value);
        if (matcher.matches())
        {
          return matcher.replaceAll(entry.getValue());
        }
      }

      return ""; //$NON-NLS-1$
    }

    if ("content".equalsIgnoreCase(filterName)) //$NON-NLS-1$
    {
      return filterContentCache.computeIfAbsent(value, key -> getContent(key));
    }

    return StringFilterRegistry.INSTANCE.filter(value, filterName);
  }

  private String getContent(String value)
  {
    try
    {
      byte[] bytes = uriConverter.createInputStream(URI.createURI(value)).readAllBytes();
      for (Charset charset : new Charset[] { StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1, StandardCharsets.UTF_16 })
      {
        try
        {
          return new String(bytes, charset);
        }
        catch (Exception ex)
        {
          //$FALL-THROUGH$
        }
      }

      return new String(bytes, IOUtil.getNativeEncoding());
    }
    catch (Exception ex)
    {
      //$FALL-THROUGH$
    }

    return ""; //$NON-NLS-1$
  }
}
