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

package org.rapla.storage.impl.server;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

import org.rapla.components.util.Cancelable;
import org.rapla.components.util.Command;
import org.rapla.components.util.CommandScheduler;
import org.rapla.components.util.DateTools;
import org.rapla.components.util.SerializableDateTimeFormat;
import org.rapla.components.util.TimeInterval;
import org.rapla.components.util.Tools;
import org.rapla.components.util.iterator.IterableChain;
import org.rapla.entities.Category;
import org.rapla.entities.DependencyException;
import org.rapla.entities.Entity;
import org.rapla.entities.EntityNotFoundException;
import org.rapla.entities.LastChangedTimestamp;
import org.rapla.entities.MultiLanguageName;
import org.rapla.entities.Named;
import org.rapla.entities.Ownable;
import org.rapla.entities.RaplaObject;
import org.rapla.entities.RaplaType;
import org.rapla.entities.Timestamp;
import org.rapla.entities.UniqueKeyException;
import org.rapla.entities.User;
import org.rapla.entities.configuration.Preferences;
import org.rapla.entities.configuration.internal.PreferencesImpl;
import org.rapla.entities.domain.Allocatable;
import org.rapla.entities.domain.Appointment;
import org.rapla.entities.domain.AppointmentStartComparator;
import org.rapla.entities.domain.Permission;
import org.rapla.entities.domain.RaplaObjectAnnotations;
import org.rapla.entities.domain.Reservation;
import org.rapla.entities.domain.ResourceAnnotations;
import org.rapla.entities.domain.internal.AllocatableImpl;
import org.rapla.entities.domain.internal.AppointmentImpl;
import org.rapla.entities.domain.internal.ReservationImpl;
import org.rapla.entities.dynamictype.Attribute;
import org.rapla.entities.dynamictype.AttributeType;
import org.rapla.entities.dynamictype.Classifiable;
import org.rapla.entities.dynamictype.Classification;
import org.rapla.entities.dynamictype.ClassificationFilter;
import org.rapla.entities.dynamictype.DynamicType;
import org.rapla.entities.dynamictype.DynamicTypeAnnotations;
import org.rapla.entities.dynamictype.internal.AttributeImpl;
import org.rapla.entities.dynamictype.internal.ClassificationImpl;
import org.rapla.entities.dynamictype.internal.DynamicTypeImpl;
import org.rapla.entities.internal.CategoryImpl;
import org.rapla.entities.internal.ModifiableTimestamp;
import org.rapla.entities.internal.UserImpl;
import org.rapla.entities.storage.CannotExistWithoutTypeException;
import org.rapla.entities.storage.DynamicTypeDependant;
import org.rapla.entities.storage.EntityReferencer;
import org.rapla.entities.storage.EntityReferencer.ReferenceInfo;
import org.rapla.entities.storage.EntityResolver;
import org.rapla.entities.storage.RefEntity;
import org.rapla.entities.storage.internal.SimpleEntity;
import org.rapla.facade.Conflict;
import org.rapla.facade.RaplaComponent;
import org.rapla.framework.Disposable;
import org.rapla.framework.RaplaContext;
import org.rapla.framework.RaplaException;
import org.rapla.framework.logger.Logger;
import org.rapla.server.internal.TimeZoneConverterImpl;
import org.rapla.storage.CachableStorageOperator;
import org.rapla.storage.CachableStorageOperatorCommand;
import org.rapla.storage.IdCreator;
import org.rapla.storage.LocalCache;
import org.rapla.storage.PreferencePatch;
import org.rapla.storage.RaplaNewVersionException;
import org.rapla.storage.RaplaSecurityException;
import org.rapla.storage.UpdateEvent;
import org.rapla.storage.UpdateOperation;
import org.rapla.storage.UpdateResult;
import org.rapla.storage.impl.AbstractCachableOperator;
import org.rapla.storage.impl.EntityStore;


public abstract class LocalAbstractCachableOperator extends AbstractCachableOperator implements Disposable, CachableStorageOperator, IdCreator {
	
	/**
	 * set encryption if you want to enable password encryption. Possible values
	 * are "sha" or "md5".
	 */
	private  String encryption = "sha-1";
	private ConflictFinder conflictFinder;
	private Map<String,SortedSet<Appointment>> appointmentMap;
	private SortedSet<LastChangedTimestamp> timestampSet;
    private SortedSet<DeleteEntry> deleteSet;

	private TimeZone systemTimeZone = TimeZone.getDefault();
	private CommandScheduler scheduler;
	private Cancelable cleanConflictsTask;
    MessageDigest md;
    
	protected void addInternalTypes(LocalCache cache) throws RaplaException
    {
		{
    		DynamicTypeImpl type = new DynamicTypeImpl();
			String key = UNRESOLVED_RESOURCE_TYPE;
			type.setKey(key);
			type.setId( key);
			type.setAnnotation(DynamicTypeAnnotations.KEY_NAME_FORMAT,"{"+key + "}");
			type.getName().setName("en", "anonymous");
			type.setAnnotation(DynamicTypeAnnotations.KEY_CLASSIFICATION_TYPE, DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_PERSON);
			type.setResolver( this);
			type.setReadOnly( );
			cache.put( type);
		}
		{
			DynamicTypeImpl type = new DynamicTypeImpl();
			String key = ANONYMOUSEVENT_TYPE;
			type.setKey(key);
            type.setId( key);
			type.setAnnotation(DynamicTypeAnnotations.KEY_NAME_FORMAT,"{"+key + "}");
			type.setAnnotation(DynamicTypeAnnotations.KEY_CLASSIFICATION_TYPE, DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RESERVATION);
			type.getName().setName("en", "anonymous");
			type.setResolver( this);
			cache.put( type);
		}
		
		{
		    DynamicTypeImpl type = new DynamicTypeImpl();
		    String key = DEFAUTL_USER_TYPE;
            type.setKey(key);
            type.setId( key);
            type.setAnnotation(DynamicTypeAnnotations.KEY_CLASSIFICATION_TYPE, DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RAPLATYPE);
            //type.setAnnotation(DynamicTypeAnnotations.KEY_TRANSFERED_TO_CLIENT, DynamicTypeAnnotations.VALUE_TRANSFERED_TO_CLIENT_NEVER);
            addAttributeWithInternalId(type,"surname", AttributeType.STRING);
            addAttributeWithInternalId(type,"firstname", AttributeType.STRING);
            addAttributeWithInternalId(type,"email", AttributeType.STRING);
            type.setResolver( this);
            type.setReadOnly();
            cache.put( type);
	    }
		{
            DynamicTypeImpl type = new DynamicTypeImpl();
            String key = PERIOD_TYPE;
            type.setKey(key);
            type.setId( key);
            type.setAnnotation(DynamicTypeAnnotations.KEY_CLASSIFICATION_TYPE, DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RAPLATYPE);
            type.setAnnotation(DynamicTypeAnnotations.KEY_TRANSFERED_TO_CLIENT, null);
            addAttributeWithInternalId(type,"name", AttributeType.STRING);
            addAttributeWithInternalId(type,"start", AttributeType.DATE);
            addAttributeWithInternalId(type,"end", AttributeType.DATE);
            type.setAnnotation(DynamicTypeAnnotations.KEY_NAME_FORMAT,"{name}");
            type.setResolver( this);
            type.setReadOnly();
            cache.put( type);
        }
		{
			DynamicTypeImpl type = new DynamicTypeImpl();
			String key = SYNCHRONIZATIONTASK_TYPE;
            type.setKey(key);
            type.setId( key);
			type.setAnnotation(DynamicTypeAnnotations.KEY_CLASSIFICATION_TYPE, DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RAPLATYPE);
			type.setAnnotation(DynamicTypeAnnotations.KEY_TRANSFERED_TO_CLIENT, DynamicTypeAnnotations.VALUE_TRANSFERED_TO_CLIENT_NEVER);
			addAttributeWithInternalId(type,"objectId", AttributeType.STRING);
			addAttributeWithInternalId(type,"externalObjectId",AttributeType.STRING);
			addAttributeWithInternalId(type,"status",AttributeType.STRING);
			addAttributeWithInternalId(type,"retries", AttributeType.STRING);
			type.setResolver( this);
			type.setReadOnly();
			cache.put( type);
		}
		
    }
	
	public LocalAbstractCachableOperator(RaplaContext context, Logger logger) throws RaplaException {
		super( context, logger);
		scheduler = context.lookup( CommandScheduler.class);
		try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RaplaException( e.getMessage() ,e);
        }

	}

	public void runWithReadLock(CachableStorageOperatorCommand cmd) throws RaplaException
	{
		Lock readLock = readLock();
		try
		{
			cmd.execute( cache );
		}
		finally
		{
			unlock( readLock);
		}
	}

	public List<Reservation> getReservations(User user, Collection<Allocatable> allocatables, Date start, Date end, ClassificationFilter[] filters,Map<String,String> annotationQuery) throws RaplaException {
		boolean excludeExceptions = false;
		HashSet<Reservation> reservationSet = new HashSet<Reservation>();
		if (allocatables == null || allocatables.size() ==0) 
		{
			allocatables = Collections.singleton( null);
		}
		
        for ( Allocatable allocatable: allocatables)
        {
        	Lock readLock = readLock();
			SortedSet<Appointment> appointments;
			try
			{
				appointments = getAppointments( allocatable);
			}
			finally
			{
				unlock( readLock);
			}
			SortedSet<Appointment> appointmentSet = AppointmentImpl.getAppointments(appointments,user,start,end, excludeExceptions);
			for (Appointment appointment:appointmentSet)
			{
	            Reservation reservation = appointment.getReservation();
                if ( !match(reservation, annotationQuery) )
                {
                    continue;
                } // Ignore Templates if not explicitly requested
                // FIXME this special case should be refactored, so one can get all reservations in one method
                else if ( RaplaComponent.isTemplate( reservation) &&  (annotationQuery == null || !annotationQuery.containsKey(RaplaObjectAnnotations.KEY_TEMPLATE) ))
                {
                    continue;
                }
	            if ( !reservationSet.contains( reservation))
	            {
	            	reservationSet.add( reservation );
	            }
			}
        }
        ArrayList<Reservation> result = new ArrayList<Reservation>(reservationSet);
        removeFilteredClassifications(result, filters);
		return result;
	}

    public boolean match(Reservation reservation, Map<String, String> annotationQuery) {
        if ( annotationQuery != null)
        {
        	for (String key : annotationQuery.keySet())
        	{
        		String annotationParam = annotationQuery.get( key);
        		String annotation = reservation.getAnnotation( key);
        		if ( annotation == null || annotationParam == null)
        		{
        			if (annotationParam!= null)
        			{
        			    return false;
        			}
        		}
        		else
        		{
        			if ( !annotation.equals(annotationParam))
        			{
                        return false;
        			}
        		}
        	}
        }
        return true;
    }

	public Collection<String> getTemplateNames() throws RaplaException {
    	Lock readLock = readLock();
    	Collection<? extends Reservation> reservations;
    	try
    	{
    		reservations = cache.getReservations();
    	}
    	finally 
    	{
    		unlock(readLock);
    	}    		
		//Reservation[] reservations = cache.getReservations(user, start, end, filters.toArray(ClassificationFilter.CLASSIFICATIONFILTER_ARRAY));
        
    	Set<String> templates = new LinkedHashSet<String>();
        for ( Reservation r:reservations)
        {
        	String templateName = r.getAnnotation(RaplaObjectAnnotations.KEY_TEMPLATE);
        	if ( templateName != null)
        	{
				templates.add( templateName);
        	}
        }
        return templates;
	}
	
	@Override
	public String createId(RaplaType raplaType) throws RaplaException {
	    String string = UUID.randomUUID().toString();
	    Character firstLetter = raplaType.getFirstLetter();
	    String result = firstLetter + string.substring(1);
        return result;
	}
	
	public String createId(RaplaType raplaType,String seed) throws RaplaException {
	  
	    byte[] data = new byte[16];
	    data = md.digest( seed.getBytes());
	    if ( data.length != 16 )
	    {
	        throw new RaplaException("Wrong algorithm");
	    }
	    data[6]  &= 0x0f;  /* clear version        */
        data[6]  |= 0x40;  /* set to version 4     */
        data[8]  &= 0x3f;  /* clear variant        */
        data[8]  |= 0x80;  /* set to IETF variant  */
        
        long msb = 0;
        long lsb = 0;
        for (int i=0; i<8; i++)
            msb = (msb << 8) | (data[i] & 0xff);
        for (int i=8; i<16; i++)
            lsb = (lsb << 8) | (data[i] & 0xff);
        long mostSigBits = msb;
        long leastSigBits = lsb;
        
        UUID uuid = new UUID( mostSigBits, leastSigBits);
	    return uuid.toString();
    }
	
	public String[] createIdentifier(RaplaType raplaType, int count) throws RaplaException {
        String[] ids = new String[ count];
    	for ( int i=0;i<count;i++)
        {
        	ids[i] = createId(raplaType);
        }
        return ids;
    }
	
	public Date today() {
		long time = getCurrentTimestamp().getTime();
		long offset = TimeZoneConverterImpl.getOffset( DateTools.getTimeZone(), systemTimeZone, time);
		Date raplaTime = new Date(time + offset);
		return DateTools.cutDate( raplaTime);
	}
	
	public Date getCurrentTimestamp() {
		long time = System.currentTimeMillis();
		return new Date( time); 
	}

	public void setTimeZone( TimeZone timeZone)
	{
		systemTimeZone = timeZone;
	}
	
	public TimeZone getTimeZone()
	{
		return systemTimeZone;
	}
	
	public String authenticate(String username, String password)
			throws RaplaException {
		Lock readLock = readLock();
		try {
			getLogger().info("Check password for User " + username);
			User user = cache.getUser(username);
			if (user != null)
			{
				String userId = user.getId();
				if (checkPassword(userId, password)) 
				{
					return userId;
				}
			}
			getLogger().warn("Login failed for " + username);
			throw new RaplaSecurityException(i18n.getString("error.login"));
		}
		finally
		{
			unlock( readLock );
		}
	}

	public boolean canChangePassword() throws RaplaException {
		return true;
	}

	public void changePassword(User user, char[] oldPassword,char[] newPassword) throws RaplaException {
		getLogger().info("Change password for User " + user.getUsername());
		String userId = user.getId();
		String password = new String(newPassword);
		if (encryption != null)
			password = encrypt(encryption, password);
		Lock writeLock = writeLock(  );
		try
		{
			cache.putPassword(userId, password);
		}
		finally
		{
			unlock( writeLock );
		}
		User editObject = editObject(user, null);
		List<Entity> editList = new ArrayList<Entity>(1);
		editList.add(editObject);
		Collection<Entity>removeList = Collections.emptyList();
		// synchronization will be done in the dispatch method
		storeAndRemove(editList, removeList, user);
	}

	public void changeName(User user, String title,String firstname, String surname) throws RaplaException {
		User editableUser = editObject(user,  user);
		Allocatable personReference =  editableUser.getPerson();
		if (personReference == null) {
			editableUser.setName(surname);
			storeUser(editableUser);
		} else {
			Allocatable editablePerson = editObject(personReference,	null);
			Classification classification = editablePerson.getClassification();
			{
				Attribute attribute = classification.getAttribute("title");
				if (attribute != null) {
					classification.setValue(attribute, title);
				}
			}
			{
				Attribute attribute = classification.getAttribute("firstname");
				if (attribute != null) {
					classification.setValue(attribute, firstname);
				}
			}
			{
				Attribute attribute = classification.getAttribute("surname");
				if (attribute != null) {
					classification.setValue(attribute, surname);
				}
			}
			ArrayList<Entity> arrayList = new ArrayList<Entity>();
			arrayList.add(editableUser);
			arrayList.add(editablePerson);
			Collection<Entity> storeObjects = arrayList;
			Collection<Entity> removeObjects = Collections.emptySet();
			// synchronization will be done in the dispatch method
			storeAndRemove(storeObjects, removeObjects, null);
		}
	}

	public void changeEmail(User user, String newEmail)	throws RaplaException {
		User editableUser = user.isReadOnly() ? editObject(user, (User) user) : user;
		Allocatable personReference = editableUser.getPerson();
		ArrayList<Entity>arrayList = new ArrayList<Entity>();
		Collection<Entity>storeObjects = arrayList;
		Collection<Entity>removeObjects = Collections.emptySet();
		storeObjects.add(editableUser);
		if (personReference == null) {
			editableUser.setEmail(newEmail);
		} else {
			Allocatable editablePerson = editObject(personReference,	null);
			Classification classification = editablePerson.getClassification();
			classification.setValue("email", newEmail);
			storeObjects.add(editablePerson);
		}
		storeAndRemove(storeObjects, removeObjects, null);
	}
	
	protected void resolveInitial(Collection<? extends Entity> entities,EntityResolver resolver) throws RaplaException {
		testResolve(entities);
		
		for (Entity entity: entities) {
		    if ( entity instanceof EntityReferencer)
		    {
		        ((EntityReferencer)entity).setResolver(resolver);
		    }
		}
		processUserPersonLink(entities);
		// It is important to do the read only later because some resolve might involve write to referenced objects
		for (Entity entity: entities) {
			 ((RefEntity)entity).setReadOnly();
		}
	}

    protected void processUserPersonLink(Collection<? extends Entity> entities) throws RaplaException {
        // resolve emails
		Map<String,Allocatable> resolvingMap = new HashMap<String,Allocatable>();
		for (Entity entity: entities)
    	{
			if ( entity instanceof Allocatable)
			{
				Allocatable allocatable = (Allocatable) entity;
	    		final Classification classification = allocatable.getClassification();
	    		final Attribute attribute = classification.getAttribute("email");
	    		if ( attribute != null)
	    		{
	    			final String email = (String)classification.getValue(attribute);
	    			if ( email != null )
	    			{
	    				resolvingMap.put( email, allocatable);
	    			}
	    		}
			}
        }	
		for ( Entity entity: entities)
		{
			if ( entity.getRaplaType().getTypeClass() == User.class)
			{
				User user = (User)entity;
				String email = user.getEmail();
				if ( email != null && email.trim().length() > 0)
				{
					Allocatable person = resolvingMap.get(email);
					if ( person != null)
					{
						user.setPerson(person);
					}
				}
			}
		}
    }
	
	public void confirmEmail(User user, String newEmail)	throws RaplaException {
		throw new RaplaException("Email confirmation must be done in the remotestorage class");
	}
	
    public Collection<Conflict> getConflicts(User user) throws RaplaException
    {
    	Lock readLock = readLock();
    	try
		{
			return conflictFinder.getConflicts( user);
		}
		finally
		{
			unlock( readLock );
		}			
    }
        
    boolean disposing;
    public void dispose() {
    	// prevent reentrance in dispose
    	synchronized ( this)
    	{
	    	if ( disposing)
	    	{
	    		getLogger().warn("Disposing is called twice",new RaplaException(""));
	    		return;
	    	}
	    	disposing = true;
    	}
    	try
    	{
    		if ( cleanConflictsTask != null)
    		{
    			cleanConflictsTask.cancel();
    		}
    		forceDisconnect();
    	}
    	finally
    	{
    		disposing = false;
    	}
    }

    protected void forceDisconnect() {
        try 
        {
            disconnect();
        } 
        catch (Exception ex) 
        {
            getLogger().error("Error during disconnect ", ex);
        }
    }

    /** performs Integrity constraints check */
	protected void check(final UpdateEvent evt, final EntityStore store) throws RaplaException {
		Set<Entity> storeObjects = new HashSet<Entity>(evt.getStoreObjects());
		//Set<Entity> removeObjects = new HashSet<Entity>(evt.getRemoveObjects());
		setResolverAndCheckReferences(evt, store);
		checkConsistency(evt, store);
		checkUnique(evt,store);
		checkNoDependencies(evt, store);
		checkVersions(storeObjects);
	}
	
	protected void updateLastChanged(UpdateEvent evt) throws RaplaException {
        Date currentTime = getCurrentTimestamp();
        String userId = evt.getUserId();
        User lastChangedBy =  ( userId != null) ?  resolve(userId,User.class) : null;
        
        for ( Entity e: evt.getStoreObjects())
        {
            if ( e instanceof ModifiableTimestamp)
            {
                ModifiableTimestamp modifiableTimestamp = (ModifiableTimestamp)e;
                Date lastChangeTime = modifiableTimestamp.getLastChanged();
                if ( lastChangeTime != null && lastChangeTime.equals( currentTime))
                {
                    // wait 1 ms to increase timestamp
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e1) {
                        throw new RaplaException( e1.getMessage(), e1);
                    }
                    currentTime = getCurrentTimestamp();
                }
                modifiableTimestamp.setLastChanged( currentTime);
                modifiableTimestamp.setLastChangedBy( lastChangedBy );
            }
        }
        for ( PreferencePatch patch: evt.getPreferencePatches())
        {
            patch.setLastChanged( currentTime );
        }
    }


	
	class TimestampComparator implements Comparator<LastChangedTimestamp>
	{
		public int compare(LastChangedTimestamp o1, LastChangedTimestamp o2) {
			if ( o1 == o2)
			{
				return 0;
			}
			Date d1 = o1.getLastChanged();
			Date d2 = o2.getLastChanged();
			// if d1 is null and d2 is not then d1 is before d2
			if ( d1 == null && d2 != null  )
			{
				return -1;
			}
			// if d2 is null and d1 is not then d2 is before d1
			if ( d1 != null  && d2 == null)
			{
				return 1;
			}
			if ( d1 != null && d2 != null)
			{
				int result =  d1.compareTo( d2);
				if ( result != 0)
				{
					return result;
				}
			}
			String id1 =  (o1 instanceof Entity) ? ((Entity)o1).getId() : o1.toString();
			String id2 = (o2 instanceof Entity) ?  ((Entity)o2).getId() : o2.toString();
		     if ( id1 == null)
		     {
		       	 if ( id2 == null)
		       	 {
		       		throw new IllegalStateException("Can't compare two entities without ids");
		       	 }
		       	 else
		       	 {
		       		return -1; 
		       	 }
		     }
		     else if ( id2 == null)
		     {
		    	 return 1;
		     }
		     return id1.compareTo( id2 );
		}
		
	}
	
	protected void initIndizes() {
		timestampSet = new TreeSet<LastChangedTimestamp>(new TimestampComparator());
		deleteSet = new TreeSet<DeleteEntry>();

		timestampSet.addAll( cache.getDynamicTypes());
		timestampSet.addAll( cache.getReservations());
		timestampSet.addAll( cache.getAllocatables());
		timestampSet.addAll( cache.getUsers());
		// The appointment map
		appointmentMap = new HashMap<String, SortedSet<Appointment>>();
    	for ( Reservation r: cache.getReservations())
    	{
			for ( Appointment app:((ReservationImpl)r).getAppointmentList())
			{
				Reservation reservation = app.getReservation();
				Allocatable[] allocatables = reservation.getAllocatablesFor(app);
				{
					Collection<Appointment> list = getAndCreateList(appointmentMap,null);
					list.add( app);
				}
				for ( Allocatable alloc:allocatables)
				{
					Collection<Appointment> list = getAndCreateList(appointmentMap,alloc);
					list.add( app);
				}
			}
    	}
		Date today2 = today();
		AllocationMap allocationMap = new AllocationMap() {
			    public SortedSet<Appointment> getAppointments(Allocatable allocatable)
			    {
			    	return LocalAbstractCachableOperator.this.getAppointments(allocatable);
			    }
			    @SuppressWarnings("unchecked")
                public Collection<Allocatable> getAllocatables()
			    {
			    	return (Collection)cache.getAllocatables();
			    }
		};
		// The conflict map
		Logger logger = getLogger();
        conflictFinder = new ConflictFinder(allocationMap, today2, logger, this);
		long delay = DateTools.MILLISECONDS_PER_HOUR;
		long period = DateTools.MILLISECONDS_PER_HOUR;
		Command cleanUpConflicts = new Command() {
			
			@Override
			public void execute() throws Exception {
				removeOldConflicts();
			}
		};
		cleanConflictsTask = scheduler.schedule( cleanUpConflicts, delay, period);
	}
	
	/** updates the bindings of the resources and returns a map with all processed allocation changes*/
	private void updateIndizes(UpdateResult result) {
		Map<Allocatable,AllocationChange> toUpdate = new HashMap<Allocatable,AllocationChange>();
		List<Allocatable> removedAllocatables = new ArrayList<Allocatable>();
		for (UpdateOperation operation: result.getOperations())
		{
			Entity current = operation.getCurrent();
			RaplaType raplaType = current.getRaplaType();
			if ( raplaType ==  Reservation.TYPE )
			{
				if ( operation instanceof UpdateResult.Remove)
				{
					Reservation old = (Reservation) current;
					for ( Appointment app: old.getAppointments() )
					{
						updateBindings( toUpdate, old, app, true);
					}
				}
				if ( operation instanceof UpdateResult.Add)
				{
					Reservation newReservation = (Reservation) ((UpdateResult.Add) operation).getNew();
					for ( Appointment app: newReservation.getAppointments() )
					{
						updateBindings( toUpdate, newReservation,app, false);
					}
				}
				if ( operation instanceof UpdateResult.Change)
				{
					Reservation oldReservation = (Reservation) ((UpdateResult.Change) operation).getOld();
					Reservation newReservation =(Reservation) ((UpdateResult.Change) operation).getNew();
					Appointment[] oldAppointments =  oldReservation.getAppointments();
					for ( Appointment oldApp: oldAppointments)
					{
						updateBindings( toUpdate, oldReservation, oldApp, true);
					}
					Appointment[] newAppointments =  newReservation.getAppointments();
					for ( Appointment newApp: newAppointments)
					{
						updateBindings( toUpdate, newReservation, newApp, false);
					}
				}
			}
			if ( raplaType ==  DynamicType.TYPE )
            {
                if ( operation instanceof UpdateResult.Change)
                {
                    DynamicType dynamicType = (DynamicType)current;
                    DynamicType old = (DynamicType)((UpdateResult.Change) operation).getOld();
                    String conflictsNew = dynamicType.getAnnotation( DynamicTypeAnnotations.KEY_CONFLICTS);
                    String conflictsOld = old.getAnnotation( DynamicTypeAnnotations.KEY_CONFLICTS);
                    if ( conflictsNew != conflictsOld)
                    {
                        if ( conflictsNew == null || conflictsOld == null || !conflictsNew.equals(conflictsOld))
                        {
                            Collection<Reservation> reservations = cache.getReservations();
                            for ( Reservation reservation:reservations)
                            {
                                if ( dynamicType.equals(reservation.getClassification().getType()))
                                {
                                    Collection<AppointmentImpl> appointments = ((ReservationImpl)reservation).getAppointmentList();
                                    for ( Appointment app: appointments )
                                    {
                                        updateBindings( toUpdate, reservation,app, true);
                                    }
                                    for ( Appointment app: appointments )
                                    {
                                        updateBindings( toUpdate, reservation,app, false);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
			if ( raplaType ==  Allocatable.TYPE )
			{
				if ( operation instanceof UpdateResult.Remove)
				{
					Allocatable old = (Allocatable) current;
					removedAllocatables.add( old);
				}
			}
			if (raplaType == Allocatable.TYPE || raplaType == Reservation.TYPE || raplaType == DynamicType.TYPE || raplaType == User.TYPE || raplaType == Preferences.TYPE || raplaType == Category.TYPE )
			{
				if ( operation instanceof UpdateResult.Remove)
				{
					LastChangedTimestamp old = (LastChangedTimestamp) current;
					timestampSet.remove( old);
					addToDelete( current);
				}
				if ( operation instanceof UpdateResult.Add)
				{
					Timestamp newEntity = (Timestamp) ((UpdateResult.Add) operation).getNew();
					timestampSet.add( newEntity);
				}
				if ( operation instanceof UpdateResult.Change)
				{
					Timestamp newEntity = (Timestamp) ((UpdateResult.Change) operation).getNew();
					LastChangedTimestamp oldEntity = (LastChangedTimestamp) ((UpdateResult.Change) operation).getOld();
					timestampSet.remove( oldEntity);
					timestampSet.add( newEntity);
				}
			}
		}

		for ( Allocatable alloc: removedAllocatables)
		{
			SortedSet<Appointment> sortedSet = appointmentMap.get( alloc);
			if ( sortedSet != null && !sortedSet.isEmpty())
			{
				getLogger().error("Removing non empty appointment map for resource " +  alloc + " Appointments:" + sortedSet);
			}
			appointmentMap.remove( alloc);
		}
	   	Date today = today();
	   	// processes the conflicts and adds the changes to the result
		conflictFinder.updateConflicts(toUpdate,result, today, removedAllocatables);
		for (Entity removed:result.getRemoved())
		{
		    if ( removed.getRaplaType() == Conflict.TYPE)
		    {
		        addToDelete( removed);
		    }
		}
		checkAbandonedAppointments();
	}
	
	private void addToDelete(Entity current) {
	    Class typeClass = current.getRaplaType().getTypeClass();
        @SuppressWarnings("unchecked")
        Class<? extends Entity> type = (Class<? extends Entity>) typeClass;
	    DeleteEntry entry = new DeleteEntry();
	    entry.deleteTime = getCurrentTimestamp();
	    String id = current.getId();
        ReferenceInfo ref = new ReferenceInfo(id, type);
        entry.deletedReference = ref;
        deleteSet.add( entry );
    }
	
    @Override
	public Collection<ReferenceInfo> getDeletedEntities(User user,final Date timestamp) throws RaplaException {
        DeleteEntry fromElement = new DeleteEntry();
        Class<? extends Entity> type = Allocatable.class;
        String id = "";
        fromElement.deletedReference = new ReferenceInfo(id, type);
        fromElement.deleteTime = timestamp;
        SortedSet<DeleteEntry> tailSet = deleteSet.tailSet( fromElement);
        LinkedList<ReferenceInfo> result = new LinkedList<ReferenceInfo>();
        for ( DeleteEntry entry:tailSet)
        {
            ReferenceInfo deletedReference = entry.deletedReference;
            // TODO check user and group rights
            result.add( deletedReference);
        }
        return result;
    }
    
    class DeleteEntry implements Comparable<DeleteEntry>
    {
        Date deleteTime;
        ReferenceInfo deletedReference;
        Set<String> affectedGroupIds; 
        Set<String> affectedUserIds;
        
        @Override
        public int compareTo(DeleteEntry o) {
            if ( o == this)
            {
                return 0 ;
            }
            Date time1 = this.deleteTime;
            Date time2 = o.deleteTime;
            int result =  time1.compareTo( time2);
            if ( result != 0)
            {
                return result;
            }
            String deleteId = getId();
            result = deleteId.compareTo( o.getId());
            return result;
        }
        
        String getId()
        {
            return deletedReference.getId();
        }
        
        @Override
        public boolean equals(Object o) 
        {
            boolean equals = getId().equals( ((DeleteEntry)o).getId());
            return equals;
        }
        
        @Override
        public int hashCode() {
            return getId().hashCode();
        }
        
        @Override
        public String toString() {
            return deletedReference + " removed on " + deleteTime;
        }
    }
	
	@Override
	public Collection<Entity> getUpdatedEntities(final Date timestamp) throws RaplaException {

		LastChangedTimestamp fromElement = new LastChangedTimestamp() {
			@Override
			public Date getLastChanged() {
				return timestamp;
			}
		};
		Lock lock = readLock();
		Collection<Entity> result = new ArrayList<Entity>();
		try
		{
			SortedSet<LastChangedTimestamp> tailSet = timestampSet.tailSet(fromElement);
			for ( LastChangedTimestamp entry : tailSet)
			{
				result.add( (Entity) entry );
			}
		}
		finally
		{
			unlock(lock);
		}
		return result;
	}

	protected void updateBindings(Map<Allocatable, AllocationChange> toUpdate,Reservation reservation,Appointment app, boolean remove)  {
		
		Set<Allocatable> allocatablesToProcess = new HashSet<Allocatable>();
		allocatablesToProcess.add( null);
		if ( reservation != null)
		{
			Allocatable[] allocatablesFor = reservation.getAllocatablesFor( app);
			allocatablesToProcess.addAll( Arrays.asList(allocatablesFor));
			// This double check is very imperformant and will be removed in the future, if it doesnt show in test runs
//			if ( remove)
//			{
//				Collection<Allocatable> allocatables = cache.getCollection(Allocatable.class);
//				for ( Allocatable allocatable:allocatables)
//				{
//					SortedSet<Appointment> appointmentSet = this.appointmentMap.get( allocatable.getId());
//					if ( appointmentSet == null)
//					{
//						continue;
//					}
//					for (Appointment app1:appointmentSet)
//					{
//						if ( app1.equals( app))
//						{
//							if ( !allocatablesToProcess.contains( allocatable))
//							{
//								getLogger().error("Old reservation " + reservation.toString() + " has not the correct allocatable information. Using full search for appointment " + app + " and resource " + allocatable ) ;
//								allocatablesToProcess.add(allocatable);
//							}
//						}
//					}
//				}
//			}
		}
		else
		{
			getLogger().error("Appointment without reservation found " + app + " ignoring.");
		}
		
		for ( Allocatable allocatable: allocatablesToProcess)
		{
			AllocationChange updateSet;
			if ( allocatable != null)
			{
				updateSet = toUpdate.get( allocatable);
				if ( updateSet == null)
				{
					updateSet = new AllocationChange();
					toUpdate.put(allocatable, updateSet);
				}
			}
			else
			{
				updateSet = null;
			}
			if ( remove)
			{
				Collection<Appointment> appointmentSet = getAndCreateList(appointmentMap,allocatable);
				// binary search could fail if the appointment has changed since the last add, which should not 
				// happen as we only put and search immutable objects in the map. But the method is left here as a failsafe 
				// with a log messaget
				if (!appointmentSet.remove( app)) 
				{
					getLogger().error("Appointent has changed, so its not found in indexed binding map. Removing via full search");
					// so we need to traverse all appointment
					Iterator<Appointment> it = appointmentSet.iterator();
					while (it.hasNext())
					{
						if (app.equals(it.next())) {
							it.remove();
							break;
						}
					}
				}
				if ( updateSet != null)
				{
					updateSet.toRemove.add( app);
				}
			}
			else
			{
				SortedSet<Appointment> appointmentSet = getAndCreateList(appointmentMap, allocatable);
				appointmentSet.add(app);
				if ( updateSet != null)
				{
					updateSet.toChange.add( app);
				}
			}
		}
	}

	static final SortedSet<Appointment> EMPTY_SORTED_SET = Collections.unmodifiableSortedSet( new TreeSet<Appointment>());
	protected SortedSet<Appointment> getAppointments(Allocatable allocatable)
    {
		String allocatableId = allocatable != null ? allocatable.getId() : null;
		SortedSet<Appointment> s = appointmentMap.get( allocatableId);
    	if ( s == null)
    	{
    		return EMPTY_SORTED_SET; 
    	}
		return Collections.unmodifiableSortedSet(s);
    }

	private SortedSet<Appointment> getAndCreateList(Map<String,SortedSet<Appointment>> appointmentMap,Allocatable alloc) {
		String allocationId = alloc != null ? alloc.getId() : null;
		SortedSet<Appointment> set = appointmentMap.get( allocationId);
		if ( set == null)
		{
			set = new TreeSet<Appointment>(new AppointmentStartComparator());
			appointmentMap.put(allocationId, set);
		}
		return set;
	}
	
    @Override
	protected UpdateResult update(UpdateEvent evt) throws RaplaException {
        UpdateResult update = super.update(evt);
	   	updateIndizes(update);
		return update;
	}
    
    public void removeOldConflicts() throws RaplaException
    {
    	Map<Entity,Entity> oldEntities = new LinkedHashMap<Entity,Entity>();
		Collection<Entity>updatedEntities = new LinkedHashSet<Entity>();
		Collection<Entity>toRemove  = new LinkedHashSet<Entity>();
		TimeInterval invalidateInterval = null;
		String userId = null;
		UpdateResult result = createUpdateResult(oldEntities, updatedEntities, toRemove, invalidateInterval, userId);
		//Date today = getCurrentTimestamp();
		Date today = today();
		Lock readLock = readLock();
		try
		{
			conflictFinder.removeOldConflicts(result, today);
    	}
    	finally
    	{
    		unlock( readLock);
    	}
		fireStorageUpdated( result );
    }
	
	protected void preprocessEventStorage(final UpdateEvent evt) throws RaplaException {
		EntityStore store = new EntityStore(this, this.getSuperCategory());
    	Collection<Entity>storeObjects = evt.getStoreObjects();
		store.addAll(storeObjects);
        for (Entity entity:storeObjects) {
            if (getLogger().isDebugEnabled())
                getLogger().debug("Contextualizing " + entity);
            ((EntityReferencer)entity).setResolver( store);
            // add all child categories to store
            if ( entity instanceof Category)
            {
            	Set<Category> children = getAllCategories( (Category)entity);
            	store.addAll(children);
            }
        }
//        Collection<Entity>removeObjects = evt.getRemoveIds();
//        store.addAll( removeObjects );
//
//        for ( Entity entity:removeObjects)
//        {
//            ((EntityReferencer)entity).setResolver( store);
//        }
        // add transitve changes to event
        addClosure( evt, store );
        // check event for inconsistencies
		check( evt, store);
		// update last changed date
		updateLastChanged( evt );
	}

	/**
	 * Create a closure for all objects that should be updated. The closure
	 * contains all objects that are sub-entities of the entities and all
	 * objects and all other objects that are affected by the update: e.g.
	 * Classifiables when the DynamicType changes. The method will recursivly
	 * proceed with all discovered objects.
	 */
	protected void addClosure(final UpdateEvent evt,EntityStore store) throws RaplaException {
		Collection<Entity> storeObjects = new ArrayList<Entity>(evt.getStoreObjects());
		Collection<String> removeIds = new ArrayList<String>(evt.getRemoveIds());
        for (Entity entity: storeObjects) 
		{
            evt.putStore(entity);
            if (DynamicType.TYPE == entity.getRaplaType()) {
                DynamicTypeImpl dynamicType = (DynamicTypeImpl) entity;
                addChangedDynamicTypeDependant(evt,store, dynamicType, false);
            }
		}
		for (Entity entity: storeObjects) {
			// update old classifiables, that may not been update before via a change event
			// that could be the case if an old reservation is restored via undo but the dynamic type changed in between. 
			// The undo cache does not notice the change in type   
			if ( entity instanceof Classifiable && entity instanceof Timestamp)
			{
				Date lastChanged = ((LastChangedTimestamp) entity).getLastChanged();
				ClassificationImpl classification = (ClassificationImpl) ((Classifiable) entity).getClassification();
				DynamicTypeImpl dynamicType = classification.getType();
				Date typeLastChanged = dynamicType.getLastChanged();
				if ( typeLastChanged != null  && lastChanged != null  && typeLastChanged.after( lastChanged))
				{
					if (classification.needsChange(dynamicType))
					{
						addChangedDependencies(evt, store, dynamicType, entity, false);
					}
				}
			}
           
			
            // TODO add conversion of classification filters or other dynamictypedependent that are stored in preferences
//			for (PreferencePatch patch:evt.getPreferencePatches())
//			{
//			    for (String key: patch.keySet())
//			    {
//			        Object object = patch.get( key);
//			        if ( object instanceof DynamicTypeDependant)
//			        {
//			            
//			        }
//			    }
//			}
		}

		for (String removeId: removeIds) 
		{
		    Entity entity = store.tryResolve(removeId);
		    if  ( entity == null)
		    {
		        continue;
		    }
	        if (DynamicType.TYPE == entity.getRaplaType()) {
	            DynamicTypeImpl dynamicType = (DynamicTypeImpl) entity;
	            addChangedDynamicTypeDependant(evt, store,dynamicType, true);
	        }
	        // If entity is a user, remove the preference object
	        if (User.TYPE == entity.getRaplaType()) {
	            addRemovedUserDependant(evt, store,(User) entity);
	        }
		}
		Set<Entity> deletedCategories = getDeletedCategories(storeObjects);
		for (Entity entity: deletedCategories)
		{
			evt.putRemove(entity);
		}
	}


//	protected void setCache(final LocalCache cache) {
//		super.setCache( cache);
//		if ( idTable == null)
//		{
//			idTable = new IdTable();
//		}
//		idTable.setCache(cache);
//	}
//	

	protected void addChangedDynamicTypeDependant(UpdateEvent evt, EntityStore store,DynamicTypeImpl type, boolean toRemove) throws RaplaException {
		List<Entity> referencingEntities = getReferencingEntities( type, store);
		Iterator<Entity>it = referencingEntities.iterator();
		while (it.hasNext()) {
			Entity entity = it.next();
			if (!(entity instanceof DynamicTypeDependant)) {
				continue;
			}
			DynamicTypeDependant dependant = (DynamicTypeDependant) entity;
			// Classifiables need update?
			if (!dependant.needsChange(type) && !toRemove)
				continue;
			if (getLogger().isDebugEnabled())
				getLogger().debug("Classifiable " + entity + " needs change!");
			// Classifiables are allready on the store list
			addChangedDependencies(evt, store, type,  entity, toRemove);
		}
	}

	private void addChangedDependencies(UpdateEvent evt,EntityStore store, DynamicTypeImpl type, Entity entity,boolean toRemove) throws EntityNotFoundException, RaplaException {
		DynamicTypeDependant dependant;
		if (evt.getStoreObjects().contains(entity)) {
			dependant = (DynamicTypeDependant) evt.findEntity(entity);
		} else {
			// no, then create a clone of the classfiable object and add to list
			User user = null;
			if (evt.getUserId() != null) {
				user = resolve(cache,evt.getUserId(), User.class);
			}
			@SuppressWarnings("unchecked")
            Class<Entity> entityType = entity.getRaplaType().getTypeClass();
            Entity persistant = store.tryResolve(entity.getId(), entityType);
			dependant = (DynamicTypeDependant) editObject( entity, persistant, user);
			// replace or add the modified entity
			evt.putStore((Entity)dependant);
		} 
		if (toRemove) {
			try {
				dependant.commitRemove(type);
			} catch (CannotExistWithoutTypeException ex) {
				// getLogger().warn(ex.getMessage(),ex);
			}
		} else {
			dependant.commitChange(type);
		}
	}
	
	private void addRemovedUserDependant(UpdateEvent evt, EntityStore store,User user) {
		PreferencesImpl preferences = cache.getPreferencesForUserId(user.getId());
		if (preferences != null)
		{
			evt.putRemove(preferences);
		}
		List<Entity>referencingEntities = getReferencingEntities( user, store);
		Iterator<Entity>it = referencingEntities.iterator();
		while (it.hasNext()) {
			Entity entity = it.next();
			// Remove internal resources automatically if the owner is deleted
			if ( entity instanceof Classifiable  && entity instanceof Ownable)
			{
				DynamicType type = ((Classifiable) entity).getClassification().getType();
				if (((DynamicTypeImpl)type).isInternal())
				{
					User owner = ((Ownable)entity).getOwner();
					if ( owner != null && owner.equals( user))
					{
						evt.putRemove( entity);
						continue;
					}
				}
			}
			if (entity instanceof Timestamp) {
				Timestamp timestamp = (Timestamp) entity;
				User lastChangedBy = timestamp.getLastChangedBy();
				if ( lastChangedBy == null || !lastChangedBy.equals( user) )
				{
					continue;
				}
				if ( entity instanceof Ownable  )
				{
					 User owner = ((Ownable)entity).getOwner();
					 // we do nothing if the user is also owner,  that dependencies need to be resolved manually
					 if ( owner != null && owner.equals(user))
					 {
						 continue;
					 }
				}
				if (evt.getStoreObjects().contains(entity)) 
				{
					((SimpleEntity)evt.findEntity(entity)).setLastChangedBy(null);
				}
				else
				{
					@SuppressWarnings("unchecked")
                    Class<? extends Entity> typeClass = entity.getRaplaType().getTypeClass();
                    Entity persistant= cache.tryResolve( entity.getId(), typeClass);
					Entity dependant = editObject( entity, persistant, user);
					((SimpleEntity)dependant).setLastChangedBy( null );
					evt.putStore(entity);
				}
			}
		}
		
	}

	/**
	 * returns all entities that depend one the passed entities. In most cases
	 * one object depends on an other object if it has a reference to it.
	 * 
	 * @param entity
	 */
	final protected Set<Entity> getDependencies(Entity entity, EntityStore store)  {
		RaplaType type = entity.getRaplaType();
		final Collection<Entity>referencingEntities;
		if (Category.TYPE == type || DynamicType.TYPE == type || Allocatable.TYPE == type || User.TYPE == type) {
		    HashSet<Entity> dependencyList = new HashSet<Entity>();
			referencingEntities = getReferencingEntities(entity, store);
	        dependencyList.addAll(referencingEntities);
	        return dependencyList;
		} 
		return Collections.emptySet();
	}

	private List<Entity> getReferencingEntities(Entity entity, EntityStore store) {
		List<Entity> result = new ArrayList<Entity>();
		addReferers(cache.getReservations(), entity, result);
		addReferers(cache.getAllocatables(), entity, result);
        Collection<User> users = cache.getUsers();
        addReferers(users, entity, result);
        addReferers(cache.getDynamicTypes(), entity, result);
      
        List<Preferences> preferenceList = new ArrayList<Preferences>();
        for ( User user:users)
        {
            PreferencesImpl preferences = cache.getPreferencesForUserId( user.getId() );
            if ( preferences != null)
            {
                preferenceList.add( preferences);
            }
        }
        PreferencesImpl systemPreferences = cache.getPreferencesForUserId( null);
        if ( systemPreferences != null)
        {
            preferenceList.add( systemPreferences );
        }
        addReferers(preferenceList, entity, result);
		return result;
	}

	private void addReferers(Iterable<? extends Entity> refererList,Entity object, List<Entity> result) {
        for ( Entity referer: refererList)
        {
            if (referer != null && !referer.isIdentical(object) )
            {
                for (ReferenceInfo info:((EntityReferencer)referer).getReferenceInfo())
                {
                    if (info.isReferenceOf(object) )
                    {
                        result.add(referer);
                    }
                }
            }
        }
    }


	private int countDynamicTypes(Collection<? extends RaplaObject> entities, String classificationType) throws RaplaException {
		Iterator<? extends RaplaObject> it = entities.iterator();
		int count = 0;
		while (it.hasNext()) {
			RaplaObject entity = it.next();
			if (DynamicType.TYPE != entity.getRaplaType())
				continue;
			DynamicType type = (DynamicType) entity;
			String annotation = type.getAnnotation(DynamicTypeAnnotations.KEY_CLASSIFICATION_TYPE);
			if ( annotation == null)
			{
				throw new RaplaException(DynamicTypeAnnotations.KEY_CLASSIFICATION_TYPE + " not set for " + type);
			}
			if (annotation.equals(	classificationType)) {
				count++;
			}
		}
		return count;
	}

	// Count dynamic-types to ensure that there is least one dynamic type left
	private void checkDynamicType(Collection<Entity>entities, String[] classificationTypes) throws RaplaException {
		int count = 0;
		for ( String classificationType: classificationTypes)
		{
			count += countDynamicTypes(entities, classificationType);
		}
		Collection<? extends DynamicType> allTypes = cache.getDynamicTypes();
		int countAll = 0;
		for ( String classificationType: classificationTypes)
		{
			countAll = countDynamicTypes(allTypes, classificationType);
		}
		if (count >= 0	&& count >= countAll) {
			throw new RaplaException(i18n.getString("error.one_type_requiered"));
		}
	}

	/**
	 * Check if the references of each entity refers to an object in cache or in
	 * the passed collection.
	 */
	final protected void setResolverAndCheckReferences(UpdateEvent evt, EntityStore store)	throws RaplaException {
		
		for (EntityReferencer entity: evt.getEntityReferences()) {
		    entity.setResolver( store );
			for (ReferenceInfo info: entity.getReferenceInfo())
			{				
				String id = info.getId();
                // Reference in cache or store?
				if (store.tryResolve(id, info.getType()) != null)
					continue;
			
				throw new EntityNotFoundException(i18n.format("error.reference_not_stored", info.getType() + ":" + id));
			}
		}
	}

	/**
	 * check if we find an object with the same name. If a different object
	 * (different id) with the same unique attributes is found a
	 * UniqueKeyException will be thrown.
	 */
	final protected void checkUnique(final UpdateEvent evt, final EntityStore store)	throws RaplaException {
		for (Entity entity : evt.getStoreObjects()) {
			String name = "";
			Entity entity2 = null;
			if (DynamicType.TYPE == entity.getRaplaType()) {
				DynamicType type = (DynamicType) entity;
				name = type.getKey();
				entity2 = (Entity) store.getDynamicType(name);
				if (entity2 != null && !entity2.equals(entity))
					throwNotUnique(name);
			}

			if (Category.TYPE == entity.getRaplaType()) {
				Category category = (Category) entity;
				Category[] categories = category.getCategories();
				for (int i = 0; i < categories.length; i++) {
					String key = categories[i].getKey();
					for (int j = i + 1; j < categories.length; j++) {
						String key2 = categories[j].getKey();
						if (key == key2 || (key != null && key.equals(key2)) ) {
							throwNotUnique(key);
						}
					}
				}
			}

			if (User.TYPE == entity.getRaplaType()) {
				name = ((User) entity).getUsername();
				if (name == null || name.trim().length() == 0) {
					String message = i18n.format("error.no_entry_for", getString("username"));
					throw new RaplaException(message);
				}
				// FIXME Replace with store.getUser for the rare case that two users with the same username are stored in one operation
				entity2 = cache.getUser(name);
				if (entity2 != null && !entity2.equals(entity))
					throwNotUnique(name);
			}
		}
	}

	private void throwNotUnique(String name) throws UniqueKeyException {
		throw new UniqueKeyException(i18n.format("error.not_unique", name));
	}

	/**
	 * compares the version of the cached entities with the versions of the new
	 * entities. Throws an Exception if the newVersion != cachedVersion
	 */
	protected void checkVersions(Collection<Entity>entities)	throws RaplaException {
		for (Entity entity: entities) {
			// Check Versions
			Entity persistantVersion = findPersistant(entity);
			// If the entities are newer, everything is o.k.
			if (persistantVersion != null && persistantVersion != entity)
			{
				if (( persistantVersion instanceof Timestamp))
				{
					Date lastChangeTimePersistant = ((LastChangedTimestamp)persistantVersion).getLastChanged();
					Date lastChangeTime = ((LastChangedTimestamp)entity).getLastChanged();
					if ( lastChangeTimePersistant != null && lastChangeTime != null && lastChangeTimePersistant.after( lastChangeTime) )
					{
						getLogger().warn(
								"There is a newer  version for: " + entity.getId()
										+ " stored version :"
										+ SerializableDateTimeFormat.INSTANCE.formatTimestamp(lastChangeTimePersistant)
										+ " version to store :" + SerializableDateTimeFormat.INSTANCE.formatTimestamp(lastChangeTime));
						throw new RaplaNewVersionException(getI18n().format(
								"error.new_version", entity.toString()));						
					}
				}

			}
		}
	}
	
	/** Check if the objects are consistent, so that they can be safely stored. */
	protected void checkConsistency(UpdateEvent evt, EntityStore store) throws RaplaException {
		Collection<EntityReferencer> entityReferences = evt.getEntityReferences();
        for (EntityReferencer referencer : entityReferences) {
		    for (ReferenceInfo referenceInfo:referencer.getReferenceInfo())
			{
				Entity reference = store.resolve( referenceInfo.getId(), referenceInfo.getType());
				if (reference instanceof Preferences
						|| reference instanceof Conflict
						|| reference instanceof Reservation
						|| reference instanceof Appointment
						)
				{
					throw new RaplaException("The current version of Rapla doesn't allow references to objects of type "	+ reference.getRaplaType());
				}
			}
		}
			
		for (Entity entity : evt.getStoreObjects()) {
		    CategoryImpl superCategory = store.getSuperCategory();
			RaplaType raplaType = entity.getRaplaType();
            if (Category.TYPE == raplaType) {
				if (entity.equals(superCategory)) {
					// Check if the user group is missing
					Category userGroups = ((Category) entity).getCategory(Permission.GROUP_CATEGORY_KEY);
					if (userGroups == null) {
						throw new RaplaException("The category with the key '"
								+ Permission.GROUP_CATEGORY_KEY
								+ "' is missing.");
					}
				} else {
					// check if the category to be stored has a parent
					Category category = (Category) entity;
					Category parent = category.getParent();
					if (parent == null) {
						throw new RaplaException("The category " + category
								+ " needs a parent.");
					}
					else 
					{
						int i = 0;
						while ( true)
						{
							if ( parent == null)
							{
								throw new RaplaException("Category needs to be a child of super category.");
							} 
							else if ( parent.equals( superCategory))
							{
								break;
							}
							parent = parent.getParent();
							i++;
							if ( i>80)
							{
								throw new RaplaException("infinite recursion detection for category " + category);
							}
						}
					}
				}
			} 
            else if ( Reservation.TYPE == raplaType)
			{
                Reservation reservation = (Reservation) entity;
                checkReservation(reservation);
			}
            else if ( DynamicType.TYPE == raplaType)
            {
                DynamicType type = (DynamicType) entity;
                DynamicTypeImpl.validate( type, i18n);
            }
		}
		
	}
	
	protected void checkReservation(Reservation reservation) throws RaplaException {
        if (reservation.getAppointments().length == 0) {
            throw new RaplaException(i18n.getString("error.no_appointment"));
        }

        Locale locale = i18n.getLocale();
        String name = reservation.getName(locale);
        if (name.trim().length() == 0) {
            throw new RaplaException(i18n.getString("error.no_reservation_name"));
        }
    }

	

	protected void checkNoDependencies(final UpdateEvent evt, final EntityStore store) throws RaplaException {
		Collection<String> removedIds = evt.getRemoveIds();
		Collection<Entity> storeObjects = new HashSet<Entity>(evt.getStoreObjects());
		HashSet<Entity> dep = new HashSet<Entity>();
		Set<Entity> deletedCategories = getDeletedCategories(storeObjects);
		Collection<Entity> removeEntities= new ArrayList<Entity>();
		for (String id:removedIds)
		{
		    Entity persistant = store.tryResolve(id);
		    if ( persistant  != null)
		    {
		        removeEntities.add( persistant );
		    }
		}
        IterableChain<Entity> iteratorChain = new IterableChain<Entity>( deletedCategories,removeEntities);

		for (Entity entity : iteratorChain) {
		    // First we add the dependencies from the stored object list
		    for (Entity obj : storeObjects) {
			    if ( obj instanceof EntityReferencer)
			    {
			        if (isRefering((EntityReferencer)obj, entity )) {
					    dep.add(obj);
			        }
			    }
			}
			// we check if the user deletes himself
			if ( entity instanceof User)
			{
			    String eventUserId = evt.getUserId();
                if (eventUserId != null && eventUserId.equals( entity.getId()))
                {
                    List<String> emptyList = Collections.emptyList();
                    throw new DependencyException(i18n.getString("error.deletehimself"), emptyList);
                }
			}

			// Than we add the dependencies from the cache. It is important that
			// we don't add the dependencies from the stored object list here,
			// because a dependency could be removed in a stored object
			Set<Entity> dependencies = getDependencies(entity, store);
			for (Entity dependency : dependencies) {
				if (!storeObjects.contains(dependency) && !removeEntities.contains( dependency)) {
					// only add the first 21 dependencies;
					if (dep.size() > MAX_DEPENDENCY )
					{
						break;
					}
					dep.add(dependency);
				}
			}
		}
		

// CKO We skip this check as the admin should have the possibility to deny a user read to allocatables objects even if he has reserved it prior 
//		for (Entity entity : storeObjects) {
//			if ( entity.getRaplaType() == Allocatable.TYPE)
//			{
//				Allocatable alloc = (Allocatable) entity;
//				for (Entity reference:getDependencies(entity))
//				{
//					if ( reference instanceof Ownable)
//					{
//						User user = ((Ownable) reference).getOwner();
//						if (user != null && !alloc.canReadOnlyInformation(user))
//						{
//							throw new DependencyException( "User " + user.getUsername() + " refers to " + getName(alloc) + ". Read permission is required.", Collections.singleton( getDependentName(reference)));
//						}
//					}
//				}
//			}
//		}
		
		if (dep.size() > 0) {
			Collection<String> names = new ArrayList<String>();
			for (Entity obj: dep)
			{				
				String string = getDependentName(obj);
				names.add(string);
			}
			throw new DependencyException(getString("error.dependencies"),names.toArray( new String[]{}));
		}
		// Count dynamic-types to ensure that there is least one dynamic type
		// for reservations and one for resources or persons
		checkDynamicType(removeEntities, new String[] {DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RESERVATION});
		checkDynamicType(removeEntities, new String[] {DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RESOURCE,DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_PERSON});
	}

	private boolean isRefering(EntityReferencer referencer, Entity entity) {
        for (ReferenceInfo info : referencer.getReferenceInfo())
        {
            if ( info.isReferenceOf(entity))
            {
                return true;
            }
        }
        return false;
    }

    private Set<Entity> getDeletedCategories(Iterable<Entity> storeObjects) {
		Set<Entity> deletedCategories = new HashSet<Entity>();
		for (Entity entity : storeObjects) {
			if ( entity.getRaplaType() == Category.TYPE)
			{
				Category newCat = (Category) entity;
				Category old = tryResolve(entity.getId(), Category.class);
				if ( old != null)
				{
					Set<Category> oldSet = getAllCategories( old);
					Set<Category> newSet = getAllCategories( newCat);
					oldSet.removeAll( newSet);
					deletedCategories.addAll( oldSet );
				}
			}
		}
		return deletedCategories;
	}

	private Set<Category> getAllCategories(Category old) {
		HashSet<Category> result = new HashSet<Category>();
		result.add( old);
		for (Category child : old.getCategories())
		{
			result.addAll( getAllCategories(child));
		}
		return result;
	}

	protected String getDependentName(Entity obj) {
		StringBuffer buf = new StringBuffer();
		if (obj instanceof Reservation) {
			buf.append(getString("reservation"));
		} else if (obj instanceof Preferences) {
			buf.append(getString("preferences"));
		} else if (obj instanceof Category) {
			buf.append(getString("categorie"));
		} else if (obj instanceof Allocatable) {
			buf.append(getString("resources_persons"));
		} else if (obj instanceof User) {
			buf.append(getString("user"));
		} else if (obj instanceof DynamicType) {
			buf.append(getString("dynamictype"));
		}
		if (obj instanceof Named) {
			Locale locale = i18n.getLocale();
			final String string = ((Named) obj).getName(locale);
			buf.append(": " + string);
		} else {
			buf.append(obj.toString());
		}
		if (obj instanceof Reservation) {
			Reservation reservation = (Reservation)obj;
			
			Appointment[] appointments = reservation.getAppointments();
			if ( appointments.length > 0)
			{
				buf.append(" ");
				Date start = appointments[0].getStart();
				buf.append(raplaLocale.formatDate(start));
			}
			
			String template = reservation.getAnnotation(RaplaObjectAnnotations.KEY_TEMPLATE);
			if ( template != null)
			{
				buf.append(" in template " + template);
			}
		}
		final Object idFull = obj.getId();
		if (idFull != null) {
			String idShort = idFull.toString();
			int dot = idShort.lastIndexOf('.');
			buf.append(" (" + idShort.substring(dot + 1) + ")");
		}
		String string = buf.toString();
		return string;
	}


	private void storeUser(User refUser) throws RaplaException {
		ArrayList<Entity> arrayList = new ArrayList<Entity>();
		arrayList.add(refUser);
		Collection<Entity> storeObjects = arrayList;
		Collection<Entity> removeObjects = Collections.emptySet();
		storeAndRemove(storeObjects, removeObjects, null);
	}
	
	protected String encrypt(String encryption, String password) throws RaplaException {
		MessageDigest md;
		try {
			md = MessageDigest.getInstance(encryption);
		} catch (NoSuchAlgorithmException ex) {
			throw new RaplaException(ex);
		}
		synchronized (md) 
		{
			md.reset();
			md.update(password.getBytes());
			return encryption + ":" + Tools.convert(md.digest());
		}
	}

	private boolean checkPassword(String userId, String password) throws RaplaException {
		if (userId == null)
			return false;

		String correct_pw = cache.getPassword(userId);
		if (correct_pw == null) {
			return false;
		}

		if (correct_pw.equals(password)) {
			return true;
		}

		int columIndex = correct_pw.indexOf(":");
		if (columIndex > 0 && correct_pw.length() > 20) {
			String encryptionGuess = correct_pw.substring(0, columIndex);
			if (encryptionGuess.contains("sha")	|| encryptionGuess.contains("md5")) {
				password = encrypt(encryptionGuess, password);
				if (correct_pw.equals(password)) {
					return true;
				}
			}
		}
		return false;
	}


	@Override
	public Map<Allocatable,Collection<Appointment>> getFirstAllocatableBindings(Collection<Allocatable> allocatables, Collection<Appointment> appointments, Collection<Reservation> ignoreList) throws RaplaException {
		Lock readLock = readLock();
		Map<Allocatable, Map<Appointment, Collection<Appointment>>> allocatableBindings;
		try
		{
			allocatableBindings = getAllocatableBindings(allocatables,	appointments, ignoreList,true);
		}
		finally
		{
			unlock( readLock);
		}
		Map<Allocatable, Collection<Appointment>> map = new HashMap<Allocatable, Collection<Appointment>>();
		for ( Map.Entry<Allocatable, Map<Appointment, Collection<Appointment>>> entry: allocatableBindings.entrySet())
		{
			Allocatable alloc = entry.getKey();
			Collection<Appointment> list = entry.getValue().keySet();
			map.put( alloc, list);
		}
		return map;
	}
	
	@Override
    public Map<Allocatable, Map<Appointment,Collection<Appointment>>> getAllAllocatableBindings(Collection<Allocatable> allocatables, Collection<Appointment> appointments, Collection<Reservation> ignoreList) throws RaplaException
    {
		Lock readLock = readLock();
		try
		{
			return getAllocatableBindings( allocatables, appointments, ignoreList, false);
	   	}
		finally
		{
			unlock( readLock );
		}
    }
	
	public Map<Allocatable, Map<Appointment,Collection<Appointment>>> getAllocatableBindings(Collection<Allocatable> allocatables,Collection<Appointment> appointments, Collection<Reservation> ignoreList, boolean onlyFirstConflictingAppointment) {
		Map<Allocatable, Map<Appointment,Collection<Appointment>>> map = new HashMap<Allocatable, Map<Appointment,Collection<Appointment>>>();
        for ( Allocatable allocatable:allocatables)
        {
            String annotation = allocatable.getAnnotation( ResourceAnnotations.KEY_CONFLICT_CREATION);
			boolean holdBackConflicts = annotation != null && annotation.equals( ResourceAnnotations.VALUE_CONFLICT_CREATION_IGNORE);
			if ( holdBackConflicts)
			{
				continue;
			}
			SortedSet<Appointment> appointmentSet = getAppointments( allocatable);
			if ( appointmentSet == null)
    		{
				continue;
    		}
			map.put(allocatable,  new HashMap<Appointment,Collection<Appointment>>() );
        	for (Appointment appointment:appointments)
        	{
    			Set<Appointment> conflictingAppointments = AppointmentImpl.getConflictingAppointments(appointmentSet, appointment, ignoreList, onlyFirstConflictingAppointment);
        		if ( conflictingAppointments.size() > 0)
        		{
	        		Map<Appointment,Collection<Appointment>> appMap = map.get( allocatable);
	        		if ( appMap == null)
	        		{
	        			appMap = new HashMap<Appointment, Collection<Appointment>>();
	        			map.put( allocatable, appMap);
	        		}
	        		appMap.put( appointment,  conflictingAppointments);
        		}
        	}
        }
        return map;
    }
	
	@Override
    public Date getNextAllocatableDate(Collection<Allocatable> allocatables,Appointment appointment,Collection<Reservation> ignoreList,Integer worktimeStartMinutes,Integer worktimeEndMinutes, Integer[] excludedDays, Integer rowsPerHour) throws RaplaException {
    	Lock readLock = readLock();
		try
		{
			Appointment newState = appointment;
			Date firstStart = appointment.getStart();
			boolean startDateExcluded = isExcluded(excludedDays, firstStart);
			boolean wholeDay = appointment.isWholeDaysSet();
			boolean inWorktime = inWorktime(appointment, worktimeStartMinutes,worktimeEndMinutes);
			if ( rowsPerHour == null || rowsPerHour <=1)
			{
				rowsPerHour = 1;
			}
			for ( int i=0;i<366*24 *rowsPerHour ;i++)
			{
				newState = ((AppointmentImpl) newState).clone();
				Date start = newState.getStart();
				long millisToAdd = wholeDay ? DateTools.MILLISECONDS_PER_DAY : (DateTools.MILLISECONDS_PER_HOUR / rowsPerHour );
				Date newStart = new Date(start.getTime() + millisToAdd);
				if (!startDateExcluded &&  isExcluded(excludedDays, newStart))
				{
					continue;
				}
				newState.move( newStart );
				if ( !wholeDay && inWorktime && !inWorktime(newState, worktimeStartMinutes, worktimeEndMinutes))
				{
					continue;
				}
				if  (!isAllocated(allocatables, newState, ignoreList))
				{
					return newStart;
				}
			}
			return null;
		}
		finally
		{
			unlock( readLock );
		}
    }

	private boolean inWorktime(Appointment appointment,
			Integer worktimeStartMinutes, Integer worktimeEndMinutes) {
		long start = appointment.getStart().getTime();
		int minuteOfDayStart = DateTools.getMinuteOfDay( start );
		long end = appointment.getEnd().getTime();
		int minuteOfDayEnd = DateTools.getMinuteOfDay( end ) + (int) DateTools.countDays(start, end) * 24 * 60;
		boolean inWorktime =  (worktimeStartMinutes == null || worktimeStartMinutes<= minuteOfDayStart) && ( worktimeEndMinutes == null || worktimeEndMinutes >= minuteOfDayEnd);
		return inWorktime;
	}

	private boolean isExcluded(Integer[] excludedDays, Date date) {
		Integer weekday = DateTools.getWeekday( date);
		if (excludedDays != null)
		{
			for ( Integer day:excludedDays)
			{
				if ( day.equals( weekday))
				{
					return true;
				}
			}
		}
		return false;
	}

	private boolean isAllocated(Collection<Allocatable> allocatables,
			Appointment appointment, Collection<Reservation> ignoreList) throws RaplaException 
	{
		Map<Allocatable, Collection<Appointment>> firstAllocatableBindings = getFirstAllocatableBindings(allocatables, Collections.singleton( appointment) , ignoreList);
		for (Map.Entry<Allocatable, Collection<Appointment>> entry: firstAllocatableBindings.entrySet())
		{
			if (entry.getValue().size() > 0)
			{
				return true;
			}
		}
		return false;
	}

    public Collection<Entity> getVisibleEntities(final User user)throws RaplaException {
		Lock readLock = readLock();
		try
		{
			return cache.getVisibleEntities(user);
		}
		finally
		{
			unlock(readLock);
		}
	}
    
    // this check is only there to detect rapla bugs in the conflict api and can be removed if it causes performance issues
    private void checkAbandonedAppointments() {
		Collection<? extends Allocatable> allocatables = cache.getAllocatables();
		Logger logger = getLogger().getChildLogger("appointmentcheck");
		try
		{
			for ( Allocatable allocatable:allocatables)
			{
				SortedSet<Appointment> appointmentSet = this.appointmentMap.get( allocatable.getId());
				if ( appointmentSet == null)
				{
					continue;
				}
				for (Appointment app:appointmentSet)
				{
					{
						SimpleEntity original = (SimpleEntity)app;
						String id = original.getId();
						if ( id == null )
						{
							logger.error( "Empty id  for " + original);
							continue;
						}
						Appointment persistant =  cache.tryResolve( id, Appointment.class );
						if ( persistant == null )
						{
							logger.error( "appointment not stored in cache " + original );
							continue;
						}
					}
					Reservation reservation = app.getReservation();
					if (reservation == null)
					{
						logger.error("Appointment without a reservation stored in cache " + app );
						appointmentSet.remove( app);
						continue;
					}
					else if (!reservation.hasAllocated( allocatable, app))
					{
						logger.error("Allocation is not stored correctly for " + reservation + " " + app + " "  + allocatable + " removing binding for " + app);
						appointmentSet.remove( app);
						continue;
					}
					else
					{
						{
							Reservation original = reservation;
							String id = original.getId();
							if ( id == null )
							{
								logger.error( "Empty id  for " + original);
								continue;
							}
							Reservation persistant = cache.tryResolve( id, Reservation.class );
							if ( persistant != null )
							{
								Date lastChanged = original.getLastChanged();
								Date persistantLastChanged = persistant.getLastChanged();
								if (persistantLastChanged != null &&  !persistantLastChanged.equals(lastChanged))
								{
									logger.error( "Reservation stored in cache is not the same as in allocation store " + original );
									continue;
								}
							}
							else
							{
								logger.error( "Reservation not stored in cache " + original + " removing binding for " + app);
								appointmentSet.remove( app);
								continue;
							}
						}
						
					}
				}
			}
		}
		catch (Exception ex)
		{
			logger.error(ex.getMessage(), ex);
		}
	}

    protected void createDefaultSystem(LocalCache cache) throws RaplaException
	{
    	EntityStore store = new EntityStore( null, cache.getSuperCategory() );
        
    	DynamicTypeImpl resourceType = newDynamicType(DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RESOURCE,"resource");
		setName(resourceType.getName(), "resource");
		add(store, resourceType);
		
		DynamicTypeImpl personType = newDynamicType(DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_PERSON,"person");
		setName(personType.getName(), "person");
		add(store, personType);
		
		DynamicTypeImpl eventType = newDynamicType(DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RESERVATION, "event");
		setName(eventType.getName(), "event");
		add(store, eventType);
		
		String[] userGroups = new String[] {Permission.GROUP_REGISTERER_KEY, Permission.GROUP_MODIFY_PREFERENCES_KEY,Permission.GROUP_CAN_READ_EVENTS_FROM_OTHERS, Permission.GROUP_CAN_CREATE_EVENTS, Permission.GROUP_CAN_EDIT_TEMPLATES};
		Date now = getCurrentTimestamp();
		CategoryImpl groupsCategory = new CategoryImpl(now,now);
		groupsCategory.setKey("user-groups");
		setName( groupsCategory.getName(), groupsCategory.getKey());
		setNew( groupsCategory);
		store.put( groupsCategory);
		for ( String catName: userGroups)
		{
			CategoryImpl group = new CategoryImpl(now,now);
			group.setKey( catName);
			setNew(group);
			setName( group.getName(), group.getKey());
			groupsCategory.addCategory( group);
			store.put( group);
		}
		cache.getSuperCategory().addCategory( groupsCategory);
		UserImpl admin = new UserImpl(now,now);
		admin.setUsername("admin");
		admin.setAdmin( true);
		setNew(admin);
		store.put( admin);
	
		Collection<Entity> list = store.getList();
		cache.putAll( list );
		testResolve( list);
	    setResolver( list);
	    
    	UserImpl user = cache.getUser("admin");
    	String password ="";
		cache.putPassword( user.getId(), password );
		cache.getSuperCategory().setReadOnly();
	
		AllocatableImpl allocatable = new AllocatableImpl(now, now);
		allocatable.setResolver( this);
		allocatable.addPermission(allocatable.newPermission());
        Classification classification = cache.getDynamicType("resource").newClassification();
        allocatable.setClassification(classification);
        setNew(allocatable);
        classification.setValue("name", getString("test_resource"));
        allocatable.setOwner( user);
        
        cache.put( allocatable);
	}
    
    private void add(EntityStore list, DynamicTypeImpl type) {
    	list.put( type);
    	for (Attribute att:type.getAttributes())
    	{
    		list.put((Entity) att);
    	}
	}
    
	private Attribute createStringAttribute(String key, String name) throws RaplaException {
		Attribute attribute = newAttribute(AttributeType.STRING, null);
		attribute.setKey(key);
		setName(attribute.getName(), name);
		return attribute;
	}
	
	private void addAttributeWithInternalId(DynamicType dynamicType,String key, AttributeType type) throws RaplaException {
	    String id = "rapla_"+ dynamicType.getKey() + "_" +key; 
        Attribute attribute = newAttribute(type, id);
        attribute.setKey(key);
		setName(attribute.getName(), key);
		dynamicType.addAttribute( attribute);
	}
	
	private DynamicTypeImpl newDynamicType(String classificationType, String key) throws RaplaException {
		DynamicTypeImpl dynamicType = new DynamicTypeImpl();
		dynamicType.setAnnotation("classification-type", classificationType);
		dynamicType.setKey(key);
		setNew(dynamicType);
		if (classificationType.equals(DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RESOURCE)) {
			dynamicType.addAttribute(createStringAttribute("name", "name"));
			dynamicType.setAnnotation(DynamicTypeAnnotations.KEY_NAME_FORMAT,"{name}");
			dynamicType.setAnnotation(DynamicTypeAnnotations.KEY_COLORS,"automatic");
		} else if (classificationType.equals(DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RESERVATION)) {
			dynamicType.addAttribute(createStringAttribute("name","eventname"));
			dynamicType.setAnnotation(DynamicTypeAnnotations.KEY_NAME_FORMAT,"{name}");
			dynamicType.setAnnotation(DynamicTypeAnnotations.KEY_COLORS, null);
		} else if (classificationType.equals(DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_PERSON)) {
			dynamicType.addAttribute(createStringAttribute("surname", "surname"));
			dynamicType.addAttribute(createStringAttribute("firstname", "firstname"));
			dynamicType.addAttribute(createStringAttribute("email", "email"));
			dynamicType.setAnnotation(DynamicTypeAnnotations.KEY_NAME_FORMAT, "{surname} {firstname}");
			dynamicType.setAnnotation(DynamicTypeAnnotations.KEY_COLORS, null);
		}
		dynamicType.setResolver( this);
		return dynamicType;
	}

	private Attribute newAttribute(AttributeType attributeType,String id)	throws RaplaException {
		AttributeImpl attribute = new AttributeImpl(attributeType);
		if ( id == null)
		{
			setNew(attribute);
		}
		else
		{
			((RefEntity)attribute).setId(id);
		}
		attribute.setResolver( this);
		return attribute;
	}
	
	private <T extends Entity> void setNew(T entity)
			throws RaplaException {

		RaplaType raplaType = entity.getRaplaType();
		String id = createIdentifier(raplaType,1)[0];
		((RefEntity)entity).setId(id);
	}
	
	
	private void setName(MultiLanguageName name, String to)
	{
		String currentLang = i18n.getLang();
		name.setName("en", to);
		try
		{
			String translation = i18n.getString( to);
			name.setName(currentLang, translation);
		}
		catch (Exception ex)
		{
			
		}
	}
	
	
}
