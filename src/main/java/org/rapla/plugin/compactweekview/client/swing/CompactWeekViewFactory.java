/*--------------------------------------------------------------------------*
 | Copyright (C) 2014 Christopher Kohlhaas                                  |
 |                                                                          |
 | This program is free software; you can redistribute it and/or modify     |
 | it under the terms of the GNU General Public License as published by the |
 | Free Software Foundation. A copy of the license has been included with   |
 | these distribution in the COPYING file, if not go to www.fsf.org         |
 |                                                                          |
 | As a special exception, you are granted the permissions to link this     |
 | program with every library, which license fulfills the Open Source       |
 | Definition as published by the Open Source Initiative (OSI).             |
 *--------------------------------------------------------------------------*/
package org.rapla.plugin.compactweekview.client.swing;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.swing.Icon;

import org.rapla.client.ReservationController;
import org.rapla.client.extensionpoints.ObjectMenuFactory;
import org.rapla.client.internal.RaplaClipboard;
import org.rapla.facade.CalendarModel;
import org.rapla.facade.CalendarSelectionModel;
import org.rapla.facade.RaplaComponent;
import org.rapla.framework.RaplaContext;
import org.rapla.framework.RaplaException;
import org.rapla.client.swing.MenuFactory;
import org.rapla.client.swing.SwingCalendarView;
import org.rapla.client.swing.extensionpoints.SwingViewFactory;
import org.rapla.client.swing.images.RaplaImages;
import org.rapla.components.calendar.DateRenderer;
import org.rapla.inject.Extension;
import org.rapla.plugin.compactweekview.CompactWeekviewPlugin;

import java.util.Set;

@Extension(provides = SwingViewFactory.class,id=CompactWeekviewPlugin.COMPACT_WEEK_VIEW)
public class CompactWeekViewFactory extends RaplaComponent implements SwingViewFactory
{

    private final Set<ObjectMenuFactory> objectMenuFactories;
    private final MenuFactory menuFactory;
    private final Provider<DateRenderer> dateRendererProvider;
    private final CalendarSelectionModel calendarSelectionModel;
    private final RaplaClipboard clipboard;
    private final ReservationController reservationController;
    @Inject
    public CompactWeekViewFactory(RaplaContext context, Set<ObjectMenuFactory> objectMenuFactories, MenuFactory menuFactory, Provider<DateRenderer> dateRendererProvider, CalendarSelectionModel calendarSelectionModel, RaplaClipboard clipboard, ReservationController reservationController)
    {
        super( context );
        this.objectMenuFactories = objectMenuFactories;
        this.menuFactory = menuFactory;
        this.dateRendererProvider = dateRendererProvider;
        this.calendarSelectionModel = calendarSelectionModel;
        this.clipboard = clipboard;
        this.reservationController = reservationController;
    }

    public SwingCalendarView createSwingView(RaplaContext context, CalendarModel model, boolean editable) throws RaplaException
    {
        return new SwingCompactWeekCalendar( context, model, editable, objectMenuFactories, menuFactory, dateRendererProvider, calendarSelectionModel, clipboard, reservationController);
    }

    public String getViewId()
    {
        return CompactWeekviewPlugin.COMPACT_WEEK_VIEW;
    }

    public String getName()
    {
        return getString(CompactWeekviewPlugin.COMPACT_WEEK_VIEW);
    }

    Icon icon;
    public Icon getIcon()
    {
        if ( icon == null) {
            icon = RaplaImages.getIcon("/org/rapla/plugin/compactweekview/images/week_compact.png");
        }
        return icon;
    }

    public String getMenuSortKey() {
        return "B1";
    }


}

