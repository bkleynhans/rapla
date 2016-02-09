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
package org.rapla.facade.tests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.rapla.components.util.DateTools;
import org.rapla.components.util.SerializableDateTimeFormat;
import org.rapla.entities.DependencyException;
import org.rapla.entities.Entity;
import org.rapla.entities.EntityNotFoundException;
import org.rapla.entities.ReadOnlyException;
import org.rapla.entities.User;
import org.rapla.entities.configuration.CalendarModelConfiguration;
import org.rapla.entities.configuration.Preferences;
import org.rapla.entities.configuration.RaplaMap;
import org.rapla.entities.domain.Allocatable;
import org.rapla.entities.domain.Appointment;
import org.rapla.entities.domain.Reservation;
import org.rapla.entities.dynamictype.Attribute;
import org.rapla.entities.dynamictype.AttributeType;
import org.rapla.entities.dynamictype.Classification;
import org.rapla.entities.dynamictype.ClassificationFilter;
import org.rapla.entities.dynamictype.ConstraintIds;
import org.rapla.entities.dynamictype.DynamicType;
import org.rapla.entities.dynamictype.DynamicTypeAnnotations;
import org.rapla.facade.CalendarSelectionModel;
import org.rapla.facade.ClientFacade;
import org.rapla.facade.Conflict;
import org.rapla.facade.RaplaFacade;
import org.rapla.facade.internal.CalendarModelImpl;
import org.rapla.framework.RaplaException;
import org.rapla.framework.TypedComponentRole;
import org.rapla.jsonrpc.common.FutureResult;
import org.rapla.plugin.weekview.WeekviewPlugin;
import org.rapla.test.util.RaplaTestCase;

@RunWith(JUnit4.class)
public class ClientFacadeTest  {
    ClientFacade clientFacade;
    RaplaFacade facade;
    Locale locale;

    @Before
    public void setUp() throws Exception {
        clientFacade = RaplaTestCase.createSimpleSimpsonsWithHomer();
        facade = clientFacade.getRaplaFacade();
        locale = Locale.getDefault();


    }


    @After
    public void tearDown() throws Exception {
        clientFacade.logout();
    }

    @Test
    public void testBelongsTo() throws Exception
    {
        Reservation[] all = facade.getReservationsForAllocatable(null, null, null, null);
        facade.removeObjects( all );
        Reservation orig =  facade.newReservation();
        final DynamicType resourceType = facade.getDynamicType("room");
        final Allocatable parentResource = facade.getAllocatables(resourceType.newClassificationFilter().toArray())[0];
        final User user = clientFacade.getUser();
        final DynamicType resourceBelongsTo = facade.newDynamicType(DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RESOURCE);
        resourceBelongsTo.setKey("roompart");
        resourceBelongsTo.getName().setName("en","Roompart");
        final Attribute attribute = facade.newAttribute(AttributeType.ALLOCATABLE);
        attribute.setKey("belongsTo");
        attribute.setConstraint(ConstraintIds.KEY_BELONGS_TO, "true");
        attribute.setConstraint(ConstraintIds.KEY_DYNAMIC_TYPE, resourceType);
        resourceBelongsTo.addAttribute( attribute );
        facade.store( resourceBelongsTo);
        final Allocatable childResource;
        {
            final Classification classification = resourceBelongsTo.newClassification();
            classification.setValue("belongsTo", parentResource);
            classification.setValue("name","erwin_left");
            final Allocatable resourceChild = facade.newAllocatable(classification, user);
            facade.store( resourceChild);
            childResource = facade.getPersistant( resourceChild);
        }
        final Allocatable childChildResource;
        {
            final Classification classification = resourceBelongsTo.newClassification();
            classification.setValue("belongsTo", childResource);
            classification.setValue("name","erwin_left_left");
            final Allocatable resourceChild = facade.newAllocatable(classification, user);
            facade.store( resourceChild);
            childChildResource = facade.getPersistant( resourceChild);
        }

        orig.getClassification().setValue("name", "new");
        Date start = DateTools.toDateTime(new Date(), new Date(DateTools.toTime(10, 0, 0)));
        Date end = DateTools.toDateTime( start,new Date(DateTools.toTime(  12,0,0)));
        orig.addAppointment( facade.newAppointment( start, end));

        orig.addAllocatable(parentResource);
        facade.store(orig);
        // simply clone the event
        Reservation newEvent;
        {
            newEvent = facade.clone(orig, user);
            Appointment firstAppointment = newEvent.getAppointments()[0];
            final Collection<Allocatable> allocatables = Arrays.asList(newEvent.getAllocatables());
            final FutureResult<Map<Allocatable, Collection<Appointment>>> allocatableBindings = facade
                    .getAllocatableBindings(allocatables, Arrays.asList(newEvent.getAppointments()));
            final Map<Allocatable, Collection<Appointment>> allocatableCollectionMap = allocatableBindings.get();
            for (Allocatable allocatable : allocatables)
            {
                final Collection<Appointment> allocatedAppointments = new HashSet(allocatableCollectionMap.get(allocatable));
                Assert.assertTrue(allocatedAppointments.contains(firstAppointment));
                Assert.assertEquals(1, allocatedAppointments.size());
            }
            facade.store(newEvent);
        }
        // clone the event and add a second appointment one day later
        {
            newEvent = facade.clone(newEvent, user);
            Appointment firstAppointment = newEvent.getAppointments()[0];
            final Appointment newAppointment = facade.newAppointment(DateTools.addDay(start), DateTools.addDay(end));
            newEvent.addAppointment(newAppointment);
            final Collection<Allocatable> allocatables = Arrays.asList(newEvent.getAllocatables());
            {
                final FutureResult<Map<Allocatable, Collection<Appointment>>> allocatableBindings = facade
                        .getAllocatableBindings(allocatables, Arrays.asList(newEvent.getAppointments()));
                final Map<Allocatable, Collection<Appointment>> allocatableCollectionMap = allocatableBindings.get();
                for (Allocatable allocatable : allocatables)
                {
                    final Collection<Appointment> allocatedAppointments = new HashSet(allocatableCollectionMap.get(allocatable));
                    Assert.assertTrue(allocatedAppointments.contains(firstAppointment));
                    Assert.assertEquals(1, allocatedAppointments.size());
                }
            }
            firstAppointment.move( newAppointment.getEnd());
            {
                final FutureResult<Map<Allocatable, Collection<Appointment>>> allocatableBindings = facade
                        .getAllocatableBindings(allocatables, Arrays.asList(newEvent.getAppointments()));
                final Map<Allocatable, Collection<Appointment>> allocatableCollectionMap = allocatableBindings.get();

                for (Allocatable allocatable : allocatables)
                {
                    final Collection<Appointment> allocatedAppointments = new HashSet(allocatableCollectionMap.get(allocatable));
                    Assert.assertEquals(0, allocatedAppointments.size());
                }
            }
        }
        // now test if the child resource also returns bindings
        {
            newEvent = facade.clone(orig, user);
            newEvent.removeAllocatable( parentResource);
            newEvent.addAllocatable(childResource);
            Appointment firstAppointment = newEvent.getAppointments()[0];
            final Collection<Allocatable> allocatables = Arrays.asList(newEvent.getAllocatables());
            final FutureResult<Map<Allocatable, Collection<Appointment>>> allocatableBindings = facade
                    .getAllocatableBindings(allocatables, Arrays.asList(newEvent.getAppointments()));
            final Map<Allocatable, Collection<Appointment>> allocatableCollectionMap = allocatableBindings.get();
            for (Allocatable allocatable : allocatables)
            {
                final Collection<Appointment> allocatedAppointments = new HashSet(allocatableCollectionMap.get(allocatable));
                Assert.assertTrue(allocatedAppointments.contains(firstAppointment));
                Assert.assertEquals(1, allocatedAppointments.size());
            }
        }
        // now test transitive
        {
            newEvent = facade.clone(orig, user);
            newEvent.removeAllocatable( parentResource);
            newEvent.addAllocatable(childChildResource);
            Appointment firstAppointment = newEvent.getAppointments()[0];
            final Collection<Allocatable> allocatables = Arrays.asList(newEvent.getAllocatables());
            final FutureResult<Map<Allocatable, Collection<Appointment>>> allocatableBindings = facade
                    .getAllocatableBindings(allocatables, Arrays.asList(newEvent.getAppointments()));
            final Map<Allocatable, Collection<Appointment>> allocatableCollectionMap = allocatableBindings.get();
            for (Allocatable allocatable : allocatables)
            {
                final Collection<Appointment> allocatedAppointments = new HashSet(allocatableCollectionMap.get(allocatable));
                Assert.assertTrue(allocatedAppointments.contains(firstAppointment));
                Assert.assertEquals(1, allocatedAppointments.size());
            }
            // and store the event
            facade.store(newEvent);
        }
        {// now check from parent
            final Appointment firstAppointment = orig.getAppointments()[0];
            final Collection<Allocatable> allocatables = Arrays.asList(orig.getAllocatables());
            final FutureResult<Map<Allocatable, Collection<Appointment>>> allocatableBindings = facade
                    .getAllocatableBindings(allocatables, Arrays.asList(firstAppointment));
            final Map<Allocatable, Collection<Appointment>> allocatableCollectionMap = allocatableBindings.get();
            for (Allocatable allocatable : allocatables)
            {
                final Collection<Appointment> allocatedAppointments = new HashSet(allocatableCollectionMap.get(allocatable));
                Assert.assertTrue(allocatedAppointments.contains(firstAppointment));
                Assert.assertEquals(1, allocatedAppointments.size());
            }
        }
        {// check that your own booking is shown
            // first remove the new stored event
            facade.remove(newEvent);
            // now add the childChildResource to parent and store it
            final Reservation edit = facade.edit(orig);
            edit.addAllocatable(childChildResource);
            facade.store(edit);
            final Appointment firstAppointment = orig.getAppointments()[0];
            final Collection<Allocatable> allocatables = Arrays.asList(orig.getAllocatables());
            final FutureResult<Map<Allocatable, Collection<Appointment>>> allocatableBindings = facade
                    .getAllocatableBindings(allocatables, Arrays.asList(firstAppointment));
            final Map<Allocatable, Collection<Appointment>> allocatableCollectionMap = allocatableBindings.get();
            for (Allocatable allocatable : allocatables)
            {
                final Collection<Appointment> allocatedAppointments = new HashSet(allocatableCollectionMap.get(allocatable));
                Assert.assertTrue(allocatedAppointments.contains(firstAppointment));
                Assert.assertEquals(1, allocatedAppointments.size());
            }
        }
    }
    
    @Test
    public void testGroup() throws Exception
    {
        // set up model
        final DynamicType newDynamicType = facade.newDynamicType("package");
        final Attribute nameAttribute = facade.newAttribute(AttributeType.STRING);
        nameAttribute.setKey("name");
        final Attribute packageAttribute = facade.newAttribute(AttributeType.ALLOCATABLE);
        packageAttribute.setKey("package");
        newDynamicType.getName().setName("en", "packageType");
        newDynamicType.addAttribute(nameAttribute);
        newDynamicType.addAttribute(packageAttribute);
        packageAttribute.setConstraint(ConstraintIds.KEY_PACKAGE, Boolean.TRUE);
        facade.store(newDynamicType);
        // create example package
        final Classification packageClassification = newDynamicType.newClassification();
        final User user = clientFacade.getUser();
        final Allocatable packageAllocatable = facade.newAllocatable(packageClassification, user);
        packageClassification.setValue(nameAttribute, "test package");
        final Allocatable[] allocatables = facade.getAllocatables();
        Collection<Allocatable> referencedAllocatables = new ArrayList<Allocatable>();
        final Allocatable alloc1 = allocatables[0];
        referencedAllocatables.add(alloc1);
        final Allocatable alloc2 = allocatables[1];
        referencedAllocatables.add(alloc2);
        packageClassification.setValues(packageAttribute, referencedAllocatables);
        facade.store(packageAllocatable);
        // check allocationBindings
        Classification classification = facade.getDynamicTypes(DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RESERVATION)[0].newClassification();
        final Reservation reservation1 = facade.newReservation(classification, user);
        {
            reservation1.addAppointment(facade.newAppointment(DateTools.toDateTime(new Date(), new Date(DateTools.toTime(10, 00, 00))), DateTools.toDateTime(new Date(), new Date(DateTools.toTime(12, 00, 00)))));
            reservation1.addAllocatable(alloc1);
            {
                Collection<Allocatable> allocatablesFromAppointment = Arrays.asList(reservation1.getAllocatables()[0]);
                Collection<Appointment> forAppointment = Arrays.asList(reservation1.getAppointmentsFor(allocatablesFromAppointment.iterator().next()));
                final FutureResult<Map<Allocatable, Collection<Appointment>>> allocatableBindings = facade.getAllocatableBindings(allocatablesFromAppointment, forAppointment);
                final Map<Allocatable, Collection<Appointment>> result = allocatableBindings.get();
                for (Allocatable allocatable : allocatablesFromAppointment)
                {
                    final Collection<Appointment> collection = result.get(allocatable);
                    Assert.assertEquals(0, collection.size());
                }
            }
            facade.store(reservation1);
        }
        final Reservation clone ;
        {// clone second with group booked
            clone = facade.clone(reservation1, user);
            clone.removeAllocatable(alloc1);
            clone.addAllocatable(packageAllocatable);
            {
                Collection<Allocatable> allocatablesFromAppointment = Arrays.asList(clone.getAllocatables()[0]);
                Collection<Appointment> forAppointment = Arrays.asList(clone.getAppointmentsFor(allocatablesFromAppointment.iterator().next()));
                final FutureResult<Map<Allocatable, Collection<Appointment>>> allocatableBindings = facade.getAllocatableBindings(allocatablesFromAppointment, forAppointment);
                final Map<Allocatable, Collection<Appointment>> result = allocatableBindings.get();
                for (Allocatable allocatable : allocatablesFromAppointment)
                {
                    final Collection<Appointment> collection = result.get(allocatable);
                    Assert.assertEquals(1, collection.size());
                }
            }
            facade.store(clone);
        }
        {// check other direction
            Collection<Allocatable> allocatablesFromAppointment = Arrays.asList(reservation1.getAllocatables()[0]);
            Collection<Appointment> forAppointment = Arrays.asList(reservation1.getAppointmentsFor(allocatablesFromAppointment.iterator().next()));
            final FutureResult<Map<Allocatable, Collection<Appointment>>> allocatableBindings = facade.getAllocatableBindings(allocatablesFromAppointment, forAppointment);
            final Map<Allocatable, Collection<Appointment>> result = allocatableBindings.get();
            for (Allocatable allocatable : allocatablesFromAppointment)
            {
                final Collection<Appointment> collection = result.get(allocatable);
                Assert.assertEquals(1, collection.size());
            }
        }
        {// remove other so clone has no conflict any more
            facade.remove(reservation1);
            Collection<Allocatable> allocatablesFromAppointment = Arrays.asList(clone.getAllocatables()[0]);
            Collection<Appointment> forAppointment = Arrays.asList(clone.getAppointmentsFor(allocatablesFromAppointment.iterator().next()));
            final FutureResult<Map<Allocatable, Collection<Appointment>>> allocatableBindings = facade.getAllocatableBindings(allocatablesFromAppointment, forAppointment);
            final Map<Allocatable, Collection<Appointment>> result = allocatableBindings.get();
            for (Allocatable allocatable : allocatablesFromAppointment)
            {
                final Collection<Appointment> collection = result.get(allocatable);
                Assert.assertEquals(0, collection.size());
            }
        }
    }
    
    @Test
    public void testConflicts() throws Exception {
        Conflict[] conflicts= facade.getConflicts( );
        Reservation[] all = facade.getReservationsForAllocatable(null, null, null, null);
        facade.removeObjects( all );
        Reservation orig =  facade.newReservation();
        orig.getClassification().setValue("name","new");
        Date start = DateTools.toDateTime(new Date(), new Date(DateTools.toTime(10, 0, 0)));
        Date end = DateTools.toDateTime( start,new Date(DateTools.toTime(  12,0,0)));
        orig.addAppointment( facade.newAppointment( start, end));

        orig.addAllocatable( facade.getAllocatables()[0]);
        facade.store(orig);
        Reservation newEvent;
        {
            newEvent = facade.clone(orig, clientFacade.getUser());
            Appointment firstAppointment = newEvent.getAppointments()[0];
            final Collection<Allocatable> allocatables = Arrays.asList(newEvent.getAllocatables());
            final FutureResult<Map<Allocatable, Collection<Appointment>>> allocatableBindings = facade
                    .getAllocatableBindings(allocatables, Arrays.asList(newEvent.getAppointments()));
            final Map<Allocatable, Collection<Appointment>> allocatableCollectionMap = allocatableBindings.get();
            facade.store(newEvent);
        }
        Conflict[] conflictsAfter = facade.getConflicts( );
        Assert.assertEquals(1, conflictsAfter.length - conflicts.length);
        HashSet<Conflict> set = new HashSet<Conflict>( Arrays.asList( conflictsAfter ));

        Assert.assertTrue(set.containsAll(new HashSet<Conflict>(Arrays.asList(conflicts))));
    }

    @Test
    public void testLogin() throws Exception {
        clientFacade.logout();
        Assert.assertEquals(false, clientFacade.login("non_existant_user", "".toCharArray()));
        Assert.assertEquals(false, clientFacade.login("non_existant_user", "fake".toCharArray()));
    }


    @Test
    public void testClone() throws Exception {
        ClassificationFilter filter = facade.getDynamicType("event").newClassificationFilter();
        filter.addEqualsRule("name","power planting");
        Reservation orig = facade.getReservationsForAllocatable(null, null, null, new ClassificationFilter[] { filter})[0];
        Reservation clone = facade.clone( orig, clientFacade.getUser() );
        Appointment a = clone.getAppointments()[0];
        Date newStart = new SerializableDateTimeFormat().parseDateTime("2005-10-10","10:20:00");
        Date newEnd = new SerializableDateTimeFormat().parseDateTime("2005-10-12", null);
        a.move( newStart );
        a.getRepeating().setEnd( newEnd );
        facade.store( clone );
        Reservation[] allPowerPlantings = facade.getReservationsForAllocatable(null,  null, null, new ClassificationFilter[] { filter});
        Assert.assertEquals(2, allPowerPlantings.length);
        Reservation[] onlyClones = facade.getReservationsForAllocatable(null,  newStart, null, new ClassificationFilter[] { filter});
        Assert.assertEquals(1, onlyClones.length);
    }



    @Test
    public void testExampleEdit() throws Exception {

    	String allocatableId;
    	String eventId;
    	{
    		Allocatable nonPersistantAllocatable = facade.newResource();
    		nonPersistantAllocatable.getClassification().setValue("name", "Bla");
    		 
    		Reservation nonPeristantEvent = facade.newReservation();
    		nonPeristantEvent.getClassification().setValue("name", "dummy-event");
    		Assert.assertEquals("event", nonPeristantEvent.getClassification().getType().getKey());
    		nonPeristantEvent.addAllocatable( nonPersistantAllocatable );
    		nonPeristantEvent.addAppointment( facade.newAppointment(new Date(), new Date()));
    		facade.storeObjects(new Entity[] { nonPersistantAllocatable, nonPeristantEvent });
    		allocatableId = nonPersistantAllocatable.getId();
    		eventId = nonPeristantEvent.getId();
    	}
    	Allocatable allocatable = facade.edit(facade.getOperator().resolve(allocatableId, Allocatable.class) );
    	
        // Store the allocatable it a second time to test if it is still modifiable after storing
        allocatable.getClassification().setValue("name", "Blubs");
        facade.store(allocatable);

        // query the allocatable from the store
        ClassificationFilter filter = facade.getDynamicType("room").newClassificationFilter();
        filter.addEqualsRule("name","Blubs");
        Allocatable persistantAllocatable = facade.getAllocatables(new ClassificationFilter[] { filter })[0];

        // query the event from the store
        ClassificationFilter eventFilter = facade.getDynamicType("event").newClassificationFilter();
        eventFilter.addEqualsRule("name","dummy-event");
        Reservation persistantEvent = facade.getReservationsForAllocatable(null, null, null, new ClassificationFilter[] { eventFilter })[0];
        // Another way to get the persistant event would have been
        //Reservation persistantEvent = facade.getPersistant( nonPeristantEvent );

        // test if the ids of editable Versions are equal to the persistant ones
        Assert.assertEquals(persistantAllocatable, allocatable);
        Reservation event = facade.getOperator().resolve( eventId, Reservation.class);
		Assert.assertEquals(persistantEvent, event);
        Assert.assertEquals(persistantEvent.getAllocatables()[0], event.getAllocatables()[0]);

//        // Check if the modifiable/original versions are different to the persistant versions
//        Assert.assertTrue( persistantAllocatable !=  allocatable );
//        Assert.assertTrue( persistantEvent !=  event );
//        Assert.assertTrue( persistantEvent.getAllocatables()[0] != event.getAllocatables()[0]);

        // Test the read only constraints
        try {
            persistantAllocatable.getClassification().setValue("name","asdflkj");
            Assert.fail("ReadOnlyException should have been thrown");
        } catch (ReadOnlyException ex) {
        }

        try {
            persistantEvent.getClassification().setValue("name","dummy-event");
            Assert.fail("ReadOnlyException should have been thrown");
        } catch (ReadOnlyException ex) {
        }

        try {
            persistantEvent.removeAllocatable( allocatable);
            Assert.fail("ReadOnlyException should have been thrown");
        } catch (ReadOnlyException ex) {
        }

        // now we get a second edit copy of the event
        Reservation nonPersistantEventVersion2 =  facade.edit(persistantEvent);
        Assert.assertTrue(nonPersistantEventVersion2 != event);

        // Both allocatables are persitant, so they have the same reference
        Assert.assertTrue(persistantEvent.getAllocatables()[0] == nonPersistantEventVersion2.getAllocatables()[0]);
    }



    @Test
    public void testNewUser() throws RaplaException {
        User newUser = facade.newUser();
        newUser.setUsername("newUser");
        try {
            facade.getPreferences(newUser);
            Assert.fail("getPreferences should throw an Exception for non existant user");
        } catch (EntityNotFoundException ex ){
        }
        facade.store( newUser );
        Preferences prefs =  facade.edit( facade.getPreferences(newUser) );
        facade.store( prefs );
    }

    @Test
    public void testPreferenceDependencies() throws RaplaException {
        Allocatable allocatable = facade.newResource();
        facade.store( allocatable);

        CalendarSelectionModel calendar = facade.newCalendarModel(clientFacade.getUser() );
        calendar.setSelectedObjects( Collections.singleton( allocatable));
        calendar.setViewId( WeekviewPlugin.WEEK_VIEW);
        CalendarModelConfiguration config = ((CalendarModelImpl)calendar).createConfiguration();

        RaplaMap<CalendarModelConfiguration> calendarList = facade.newRaplaMap( Collections.singleton( config ));

        Preferences preferences = facade.getPreferences();
        Preferences editPref  =  facade.edit( preferences );
        TypedComponentRole<RaplaMap<CalendarModelConfiguration>> TEST_ENTRY  = new TypedComponentRole<RaplaMap<CalendarModelConfiguration>>("TEST");
        editPref.putEntry(TEST_ENTRY, calendarList );
        facade.store( editPref );
        try {
            facade.remove( allocatable );
            Assert.fail("DependencyException should have thrown");
        } catch (DependencyException ex) {
        }

        calendarList = facade.newRaplaMap( new ArrayList<CalendarModelConfiguration> ());
        editPref  = facade.edit( preferences );
        editPref.putEntry( TEST_ENTRY, calendarList );
        facade.store( editPref );

        facade.remove( allocatable );
    }

    @Test
    public void testResourcesNotEmpty() throws RaplaException {
        Allocatable[] resources = facade.getAllocatables(null);
        Assert.assertTrue(resources.length > 0);
    }

    void printConflicts(Conflict[] c) {
        System.out.println(c.length + " Conflicts:");
        for (int i=0;i<c.length;i++) {
            printConflict(c[i]);
        }
    }
    void printConflict(Conflict c) {
        System.out.println("Conflict: " + c.getAppointment1()
                           + " with " + c.getAppointment2());
        System.out.println("          " + c.getAllocatable().getName(locale)) ;
        System.out.println("          " + c.getAppointment1() + " overlapps " + c.getAppointment2());
    }



}





