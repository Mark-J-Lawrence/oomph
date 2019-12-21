/*
 * Copyright (c) 2014-2016 Eike Stepper (Loehne, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 */
package org.eclipse.oomph.setup.internal.installer;

import org.eclipse.oomph.internal.ui.AccessUtil;
import org.eclipse.oomph.internal.ui.UIPlugin;
import org.eclipse.oomph.p2.core.ProfileTransaction.Resolution;
import org.eclipse.oomph.setup.User;
import org.eclipse.oomph.setup.internal.core.SetupTaskPerformer;
import org.eclipse.oomph.setup.ui.wizards.ConfirmationPage;
import org.eclipse.oomph.setup.ui.wizards.SetupWizard.SelectionMemento;
import org.eclipse.oomph.setup.ui.wizards.SetupWizardDialog;
import org.eclipse.oomph.util.ExceptionHandler;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.TraverseEvent;
import org.eclipse.swt.events.TraverseListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

/**
 * @author Eike Stepper
 */
public final class InstallerDialog extends SetupWizardDialog implements InstallerUI
{
  private final IPageChangedListener pageChangedListener = new PageChangedListener();

  private final boolean restarted;

  private ToolItem updateToolItem;

  private boolean updateSearching;

  private Resolution updateResolution;

  private IStatus updateError;

  private String version;

  private Link versionLink;

  /**
   * Used only in the generated documentation.
   */
  public InstallerDialog(Shell parentShell, boolean restarted)
  {
    this(parentShell, new Installer(new SelectionMemento()), restarted);
  }

  public InstallerDialog(Shell parentShell, Installer installer, boolean restarted)
  {
    super(parentShell, installer);
    this.restarted = restarted;
    addPageChangedListener(pageChangedListener);

    if (UIPlugin.isRecorderEnabled())
    {
      setNonModal();
    }
  }

  public Installer getInstaller()
  {
    return (Installer)getWizard();
  }

  public boolean refreshJREs()
  {
    ProductPage page = (ProductPage)getWizard().getPage(ProductPage.PAGE_NAME);
    if (page != null)
    {
      return page.refreshJREs();
    }

    return false;
  }

  @Override
  public void create()
  {
    super.create();

    final Shell shell = getShell();
    shell.addTraverseListener(new TraverseListener()
    {
      public void keyTraversed(TraverseEvent e)
      {
        if (e.detail == SWT.TRAVERSE_ESCAPE)
        {
          shell.close();
          e.detail = SWT.TRAVERSE_NONE;
          e.doit = false;
        }
      }
    });

    shell.getDisplay().asyncExec(new Runnable()
    {
      public void run()
      {
        final Installer installer = getInstaller();
        if (!shell.isDisposed())
        {
          final Runnable checkIndex = this;
          shell.getDisplay().asyncExec(new Runnable()
          {
            public void run()
            {
              if (!shell.isDisposed())
              {
                installer.getIndexLoader().awaitIndexLoad();

                UIPlugin.openRecorderIfEnabled();
                SetupInstallerPlugin.runTests();

                if (installer.getCatalogManager().getIndex() == null)
                {
                  if (installer.handleMissingIndex(shell))
                  {
                    shell.getDisplay().asyncExec(checkIndex);
                  }
                  else
                  {
                    close();
                  }
                }
              }
            }
          });
        }
      }
    });
  }

  @Override
  protected Control createHelpControl(Composite parent)
  {
    Control helpControl = super.createHelpControl(parent);
    setProductVersionLink(parent);
    return helpControl;
  }

  @Override
  protected void createToolItemsForToolBar(ToolBar toolBar)
  {
    ToolItem networkProxySettingsToolItem = createToolItem(toolBar, "install_prefs_proxy.png", "Network proxy settings");
    networkProxySettingsToolItem.addSelectionListener(new SelectionAdapter()
    {
      @Override
      public void widgetSelected(SelectionEvent e)
      {
        Dialog dialog = new NetworkConnectionsDialog(getShell());
        dialog.open();
      }
    });
    AccessUtil.setKey(networkProxySettingsToolItem, "proxy");

    ToolItem sshSettingsToolItem = createToolItem(toolBar, "install_prefs_ssh2.png", "SSH2 settings");
    sshSettingsToolItem.addSelectionListener(new SelectionAdapter()
    {
      @Override
      public void widgetSelected(SelectionEvent e)
      {
        Dialog dialog = new NetworkSSH2Dialog(getShell());
        dialog.open();
      }
    });
    AccessUtil.setKey(sshSettingsToolItem, "ssh");

    ToolItem simpleToolItem = createToolItem(toolBar, "simple.png", "Switch to simple mode");
    simpleToolItem.addSelectionListener(new SelectionAdapter()
    {
      @Override
      public void widgetSelected(SelectionEvent e)
      {
        close();
        setReturnCode(RETURN_SIMPLE);
      }
    });
    AccessUtil.setKey(simpleToolItem, "simple");

    final ToolItem webLinksToolItem = createToolItem(toolBar, URISchemeUtil.isRegistered() ? "web_links_registered.png" : "web_links_unregistered.png",
        SimpleInstallerDialog.WEB_LINKS_MENU_ITEM_DESCRIPTION);
    webLinksToolItem.addSelectionListener(new SelectionAdapter()
    {
      @Override
      public void widgetSelected(SelectionEvent e)
      {
        if (URISchemeUtil.manageRegistrations(getShell()) == URISchemeUtil.RegistrationConfirmation.KEEP_INSTALLER)
        {
          KeepInstallerDialog keepInstallerDialog = new KeepInstallerDialog(getShell(), true);
          if (keepInstallerDialog.open() == Window.OK)
          {
            close();
            return;
          }
        }

        webLinksToolItem
            .setImage(SetupInstallerPlugin.INSTANCE.getSWTImage(URISchemeUtil.isRegistered() ? "web_links_registered.png" : "web_links_unregistered.png"));
      }
    });
    AccessUtil.setKey(simpleToolItem, "web-links");

    updateToolItem = createToolItem(toolBar, "install_update0.png", "Update");
    updateToolItem.setDisabledImage(SetupInstallerPlugin.INSTANCE.getSWTImage("install_searching0.png"));
    updateToolItem.addSelectionListener(new SelectionAdapter()
    {
      @Override
      public void widgetSelected(SelectionEvent e)
      {
        selfUpdate();
      }
    });
    AccessUtil.setKey(updateToolItem, "update");
  }

  protected final ToolItem createToolItem(ToolBar toolBar, String iconPath, String toolTip)
  {
    ToolItem toolItem = new ToolItem(toolBar, SWT.PUSH);
    if (iconPath == null)
    {
      toolItem.setText(toolTip);
    }
    else
    {
      Image image = SetupInstallerPlugin.INSTANCE.getSWTImage(iconPath);
      toolItem.setImage(image);
      toolItem.setToolTipText(toolTip);
    }

    return toolItem;
  }

  private void selfUpdate()
  {
    updateError = null;
    setUpdateIcon(0);

    if (updateResolution == null)
    {
      initUpdateSearch();
    }
    else
    {
      Runnable successRunnable = new Runnable()
      {
        public void run()
        {
          restart();
        }
      };

      ExceptionHandler<CoreException> exceptionHandler = new ExceptionHandler<CoreException>()
      {
        public void handleException(CoreException ex)
        {
          updateError = ex.getStatus();
        }
      };

      Runnable finalRunnable = new Runnable()
      {
        public void run()
        {
          updateResolution = null;
          setUpdateIcon(0);
        }
      };

      SelfUpdate.update(getShell(), updateResolution, successRunnable, exceptionHandler, finalRunnable);
    }
  }

  private void initUpdateSearch()
  {
    updateSearching = true;

    Thread updateIconSetter = new UpdateIconAnimator();
    updateIconSetter.start();

    Thread updateSearcher = new UpdateSearcher();
    updateSearcher.start();
  }

  private void setUpdateIcon(final int icon)
  {
    if (!updateToolItem.isDisposed())
    {
      updateToolItem.getDisplay().asyncExec(new Runnable()
      {
        public void run()
        {
          if (updateToolItem == null || updateToolItem.isDisposed())
          {
            return;
          }

          try
          {
            if (updateSearching)
            {
              updateToolItem.setToolTipText("Checking for updates...");
              updateToolItem.setDisabledImage(SetupInstallerPlugin.INSTANCE.getSWTImage("install_searching" + icon + ".png"));
              updateToolItem.setEnabled(false);
            }
            else if (updateError != null)
            {
              StringBuilder builder = new StringBuilder();
              formatStatus(builder, "", updateError);
              updateToolItem.setToolTipText(builder.toString());
              updateToolItem.setImage(SetupInstallerPlugin.INSTANCE.getSWTImage("install_error.png"));
              updateToolItem.setEnabled(true);
            }
            else if (updateResolution != null)
            {
              updateToolItem.setToolTipText("Install available updates");
              updateToolItem.setImage(SetupInstallerPlugin.INSTANCE.getSWTImage("install_update" + icon + ".png"));
              updateToolItem.setEnabled(true);
            }
            else
            {
              updateToolItem.setToolTipText("No updates available");
              updateToolItem.setDisabledImage(SetupInstallerPlugin.INSTANCE.getSWTImage("install_update_disabled.png"));
              updateToolItem.setEnabled(false);
            }
          }
          catch (Exception ex)
          {
            // Ignore
          }
        }

        private void formatStatus(StringBuilder builder, String indent, IStatus status)
        {
          if (builder.length() != 0)
          {
            builder.append('\n');
          }

          builder.append(indent);
          builder.append(status.getMessage());

          for (IStatus child : status.getChildren())
          {
            formatStatus(builder, indent + "   ", child);
          }
        }
      });
    }
  }

  private void setProductVersionLink(Composite parent)
  {
    GridLayout parentLayout = (GridLayout)parent.getLayout();
    parentLayout.numColumns++;
    parentLayout.horizontalSpacing = 10;

    versionLink = new Link(parent, SWT.NO_FOCUS);
    versionLink.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_CENTER | GridData.VERTICAL_ALIGN_CENTER));
    versionLink.setToolTipText("About");
    versionLink.setText("<a>0.0.0</a>");
    AccessUtil.setKey(versionLink, "version");

    Thread thread = new ProductVersionInitializer();
    thread.start();
  }

  public int show()
  {
    setBlockOnOpen(false);
    open();

    getInstaller().runEventLoop(getShell());

    return getReturnCode();
  }

  public void showAbout()
  {
    new AboutDialog(getShell(), version).open();
  }

  public void restart()
  {
    close();
    setReturnCode(RETURN_RESTART);
  }

  public void reset()
  {
    close();
    setReturnCode(RETURN_ADVANCED);
  }

  /**
   * @author Eike Stepper
   */
  private final class PageChangedListener implements IPageChangedListener
  {
    public void pageChanged(PageChangedEvent event)
    {
      if (event.getSelectedPage() instanceof ConfirmationPage)
      {
        updateSearching = false;
        updateResolution = null;
        updateError = null;
        setUpdateIcon(0);

        SetupTaskPerformer performer = getInstaller().getPerformer();
        performer.getBundles().add(SetupInstallerPlugin.INSTANCE.getBundle());
      }
    }
  }

  /**
   * @author Eike Stepper
   */
  private final class ProductVersionInitializer extends Thread
  {
    public ProductVersionInitializer()
    {
      super("Product Version Initializer");
    }

    @Override
    public void run()
    {
      try
      {
        version = SelfUpdate.getProductVersion();
        if (version != null)
        {
          if (version == SelfUpdate.SELF_HOSTING)
          {
            updateSearching = false;
            setUpdateIcon(0);
          }
          else if (!restarted)
          {
            initUpdateSearch();
          }

          versionLink.getDisplay().asyncExec(new Runnable()
          {
            public void run()
            {
              try
              {
                versionLink.addSelectionListener(new SelectionAdapter()
                {
                  @Override
                  public void widgetSelected(SelectionEvent e)
                  {
                    showAbout();
                  }
                });

                versionLink.setText("<a>" + version + "</a>");
                versionLink.getParent().layout();
              }
              catch (Exception ex)
              {
                SetupInstallerPlugin.INSTANCE.log(ex);
              }
            }
          });
        }
      }
      catch (Exception ex)
      {
        SetupInstallerPlugin.INSTANCE.log(ex);
      }
    }
  }

  /**
   * @author Eike Stepper
   */
  private final class UpdateIconAnimator extends Thread
  {
    public UpdateIconAnimator()
    {
      super("Update Icon Animator");
    }

    @Override
    public void run()
    {
      try
      {
        for (int i = 0; updateSearching || updateResolution != null; i = ++i % 20)
        {
          if (updateToolItem == null || updateToolItem.isDisposed())
          {
            return;
          }

          int icon = i > 7 ? 0 : i;
          setUpdateIcon(icon);
          sleep(80);
        }

        setUpdateIcon(0);
      }
      catch (Exception ex)
      {
        SetupInstallerPlugin.INSTANCE.log(ex);
      }
    }
  }

  /**
   * @author Eike Stepper
   */
  private final class UpdateSearcher extends Thread
  {
    public UpdateSearcher()
    {
      super("Update Searcher");
    }

    @Override
    public void run()
    {
      try
      {
        User user = getInstaller().getUser();
        updateResolution = SelfUpdate.resolve(user, null);
      }
      catch (CoreException ex)
      {
        updateError = ex.getStatus();
      }
      finally
      {
        updateSearching = false;
      }
    }
  }

  @Override
  protected IDialogSettings getDialogBoundsSettings()
  {
    return SetupInstallerPlugin.INSTANCE.getDialogSettings("AdvancedInstaller");
  }

  @Override
  protected int getDialogBoundsStrategy()
  {
    return DIALOG_PERSISTSIZE;
  }

  @Override
  protected Point getInitialSize()
  {
    Point computedSize = getShell().computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
    computedSize.x = computedSize.x * 21 / 20;

    Point initialSize = super.getInitialSize();
    return new Point(Math.max(computedSize.x, initialSize.x), Math.max(computedSize.y, initialSize.y));
  }
}
