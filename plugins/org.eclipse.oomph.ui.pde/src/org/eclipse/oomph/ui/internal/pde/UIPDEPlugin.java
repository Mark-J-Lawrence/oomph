/*
 * Copyright (c) 2014 Eike Stepper (Loehne, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 */
package org.eclipse.oomph.ui.internal.pde;

import org.eclipse.oomph.ui.OomphUIPlugin;
import org.eclipse.oomph.util.internal.pde.UtilPDEPlugin;

import org.eclipse.emf.common.ui.EclipseUIPlugin;
import org.eclipse.emf.common.util.ResourceLocator;

/**
 * @author Eike Stepper
 */
public final class UIPDEPlugin extends OomphUIPlugin
{
  public static final UIPDEPlugin INSTANCE = new UIPDEPlugin();

  private static Implementation plugin;

  public UIPDEPlugin()
  {
    super(new ResourceLocator[] { UtilPDEPlugin.INSTANCE, org.eclipse.oomph.internal.ui.UIPlugin.INSTANCE });
  }

  @Override
  public ResourceLocator getPluginResourceLocator()
  {
    return plugin;
  }

  /**
   * @author Eike Stepper
   */
  public static class Implementation extends EclipseUIPlugin
  {
    public Implementation()
    {
      plugin = this;
    }
  }
}
