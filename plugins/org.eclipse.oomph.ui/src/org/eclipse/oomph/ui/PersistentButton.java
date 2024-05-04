/*
 * Copyright (c) 2014, 2015 Eike Stepper (Loehne, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 */
package org.eclipse.oomph.ui;

import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

/**
 * @author Eike Stepper
 */
public class PersistentButton
{
  private final Button button;

  private PersistentButton(Composite parent, int style, boolean defaultSelection, final Persistence persistence)
  {
    button = new Button(parent, style);
    button.setSelection(persistence != null ? persistence.load(defaultSelection) : defaultSelection);
    button.addSelectionListener(new SelectionAdapter()
    {
      @Override
      public void widgetSelected(SelectionEvent e)
      {
        if (persistence != null)
        {
          persistence.save(button.getSelection());
        }
      }
    });
  }

  public static Button create(Composite parent, int style, boolean defaultSelection, final Persistence persistence)
  {
    return new PersistentButton(parent, style, defaultSelection, persistence).button;
  }

  public static ToolItem create(ToolBar toolBar, boolean defaultSelection, final Persistence persistence)
  {
    ToolItem toolItem = new ToolItem(toolBar, SWT.CHECK);
    toolItem.setSelection(persistence.load(false));
    toolItem.addSelectionListener(new SelectionAdapter()
    {
      @Override
      public void widgetSelected(SelectionEvent e)
      {
        persistence.save(toolItem.getSelection());
      }
    });

    return toolItem;
  }

  /**
   * @author Eike Stepper
   */
  public static abstract class Persistence
  {
    protected abstract boolean load(boolean defaultSelection);

    protected abstract void save(boolean selection);
  }

  /**
   * @author Eike Stepper
   */
  public static final class DialogSettingsPersistence extends Persistence
  {
    private final IDialogSettings dialogSettings;

    private final String key;

    public DialogSettingsPersistence(IDialogSettings dialogSettings, String key)
    {
      this.dialogSettings = dialogSettings;
      this.key = key;
    }

    public IDialogSettings getDialogSettings()
    {
      return dialogSettings;
    }

    public String getKey()
    {
      return key;
    }

    @Override
    protected boolean load(boolean defaultSelection)
    {
      String value = dialogSettings.get(key);
      if (value != null)
      {
        return Boolean.parseBoolean(value);
      }

      return defaultSelection;
    }

    @Override
    protected void save(boolean selection)
    {
      dialogSettings.put(key, selection);
    }
  }

  /**
   * @author Eike Stepper
   */
  public static final class ToggleCommandPersistence extends Persistence
  {
    private final String commandID;

    public ToggleCommandPersistence(String commandID)
    {
      this.commandID = commandID;
    }

    public String getCommandID()
    {
      return commandID;
    }

    @Override
    protected boolean load(boolean defaultSelection)
    {
      return ToggleCommandHandler.getToggleState(commandID);
    }

    @Override
    protected void save(boolean selection)
    {
      ToggleCommandHandler.setToggleState(commandID, selection);
    }
  }
}
