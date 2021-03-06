/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.aerospike.core;

import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.support.PropertyComparator;
import org.springframework.beans.support.SortDefinition;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.aerospike.convert.AerospikeData;
import org.springframework.data.aerospike.convert.MappingAerospikeConverter;
import org.springframework.data.aerospike.mapping.*;
import org.springframework.data.aerospike.repository.query.AerospikeQueryCreator;
import org.springframework.data.aerospike.repository.query.Query;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.keyvalue.core.IterableConverter;
import org.springframework.data.keyvalue.core.KeyValueCallback;
import org.springframework.data.keyvalue.core.SpelPropertyComparator;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.util.CloseableIterator;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.comparator.CompoundComparator;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Info;
import com.aerospike.client.Key;
import com.aerospike.client.Operation;
import com.aerospike.client.Record;
import com.aerospike.client.ScanCallback;
import com.aerospike.client.Value;
import com.aerospike.client.cluster.Node;
import com.aerospike.client.command.ParticleType;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.aerospike.client.query.Filter;
import com.aerospike.client.query.IndexType;
import com.aerospike.client.query.KeyRecord;
import com.aerospike.client.query.RecordSet;
import com.aerospike.client.query.ResultSet;
import com.aerospike.client.query.Statement;
import com.aerospike.client.task.IndexTask;
import com.aerospike.helper.query.KeyRecordIterator;
import com.aerospike.helper.query.Qualifier;
import com.aerospike.helper.query.QueryEngine;
import com.aerospike.helper.query.Qualifier.FilterOperation;

/**
 * Primary implementation of {@link AerospikeOperations}.
 * 
 * @author Oliver Gierke
 * @author Peter Milne
 */
public class AerospikeTemplate implements AerospikeOperations {

	private static final MappingAerospikeConverter DEFAULT_CONVERTER = new MappingAerospikeConverter();

	private static final AerospikeExceptionTranslator DEFAULT_EXCEPTION_TRANSLATOR = new DefaultAerospikeExceptionTranslator();

	private final MappingContext<BasicAerospikePersistentEntity<?>, AerospikePersistentProperty> mappingContext;

	private final AerospikeClient client;

	private final MappingAerospikeConverter converter;

	private final String namespace;

	private int count = 0;

	private final QueryEngine queryEngine;

	private AerospikeExceptionTranslator exceptionTranslator;

	private WritePolicy insertPolicy;

	private WritePolicy updatePolicy;

	/**
	 * Creates a new {@link AerospikeTemplate} for the given
	 * {@link AerospikeClient}.
	 * 
	 * @param client must not be {@literal null}.
	 */
	public AerospikeTemplate(AerospikeClient client, String namespace) {
		Assert.notNull(client, "Aerospike client must not be null!");
		Assert.notNull(namespace, "Namespace cannot be null");
		Assert.hasLength(namespace);

		this.client = client;
		this.converter = DEFAULT_CONVERTER;
		this.exceptionTranslator = DEFAULT_EXCEPTION_TRANSLATOR;
		this.namespace = namespace;
		this.mappingContext = new AerospikeMappingContext();
		this.insertPolicy = new WritePolicy(this.client.writePolicyDefault);
		this.updatePolicy = new WritePolicy(this.client.writePolicyDefault);
		this.insertPolicy.recordExistsAction = RecordExistsAction.CREATE_ONLY;
		this.updatePolicy.recordExistsAction = RecordExistsAction.UPDATE_ONLY;

		this.queryEngine = new QueryEngine(this.client);

		loggerSetup();

	}

	private void loggerSetup() {
		final Logger log = LoggerFactory.getLogger(AerospikeQueryCreator.class);
		com.aerospike.client.Log
				.setCallback(new com.aerospike.client.Log.Callback() {

					@Override
					public void log(com.aerospike.client.Log.Level level,
							String message) {
						switch (level) {
						case INFO:
							log.info("AS:" + message);
							break;
						case DEBUG:
							log.debug("AS:" + message);
							break;
						case ERROR:
							log.error("AS:" + message);
							break;
						case WARN:
							log.warn("AS:" + message);
							break;
						}

					}
				});

	}

	@Override
	public void insert(Serializable id, Object objectToInsert) {
		try {
			AerospikeData data = AerospikeData.forWrite(this.namespace);
			converter.write(objectToInsert, data);
			data.setID(id);
			Key key = data.getKey();
			Bin[] bins = data.getBinsAsArray();
			client.put(this.insertPolicy, key, bins);
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
	}

	@Override
	public <T> void createIndex(Class<T> domainType, String indexName,
			String binName, IndexType indexType) {

		IndexTask task = client.createIndex(null, this.namespace,
				domainType.getSimpleName(), indexName, binName, indexType);
		task.waitTillComplete();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.springframework.data.aerospike.core.AerospikeOperations#save(java.io.
	 * Serializable, java.lang.Object, java.lang.Object)
	 */
	@Override
	public <T> void save(Serializable id, Object objectToInsert,
			Class<T> domainType) {
		Assert.notNull(id, "Id must not be null!");
		Assert.notNull(domainType, "Domain Type must not be null!");
		Assert.notNull(objectToInsert, "Object to insert must not be null!");
		try {
			AerospikeData data = AerospikeData.forWrite(this.namespace);
			converter.write(objectToInsert, data);
			data.setID(id);
            data.setSetName(AerospikeSimpleTypes.getColletionName(domainType));
			Key key = data.getKey();
			Bin[] bins = data.getBinsAsArray();
			client.put(null, key, bins);
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}

	}

	@Override
	public <T> T findById(Serializable id, Class<T> type, Class<T> domainType) {
		Assert.notNull(id, "Id must not be null!");
		Assert.notNull(type, "Class Type must not be null!");
		try {
			Key key = new Key(this.namespace, domainType.getSimpleName(),
					id.toString());
			AerospikeData data = AerospikeData.forRead(key, null);
			Record record = this.client.get(null, key);
			data.setRecord(record);
			return converter.read(type, data);
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
	}

	public void save(Serializable id, Object objectToInsert) {
		Assert.notNull(id, "Id must not be null!");
		Assert.notNull(objectToInsert, "Object to insert must not be null!");
		try {
			AerospikeData data = AerospikeData.forWrite(this.namespace);
			converter.write(objectToInsert, data);
			data.setID(id);
			Key key = data.getKey();
			Bin[] bins = data.getBinsAsArray();
			client.put(null, key, bins);
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
	}

	public <T> void insertAll(Collection<? extends T> objectsToSave) {
		for (T element : objectsToSave) {
			if (element == null) {
				continue;
			}

			insert(element);
		}
	}

	@Override
	public <T> T insert(T objectToInsert) {
		Assert.notNull(objectToInsert, "Object to insert must not be null!");
		try {
			AerospikeData data = AerospikeData.forWrite(this.namespace);
			converter.write(objectToInsert, data);
			Key key = data.getKey();
			Bin[] bins = data.getBinsAsArray();
			client.put(this.insertPolicy, key, bins);
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
		return null;
	}

	@Override
	public void update(Object objectToUpdate) {
		Assert.notNull(objectToUpdate, "Object to update must not be null!");
		try {
			AerospikeData data = AerospikeData.forWrite(this.namespace);
			converter.write(objectToUpdate, data);
			Key key = data.getKey();
			Bin[] bins = data.getBinsAsArray();
			client.put(this.updatePolicy, key, bins);
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
	}

	@Override
	public void update(Serializable id, Object objectToUpdate) {
		Assert.notNull(id, "Id must not be null!");
		Assert.notNull(objectToUpdate, "Object to update must not be null!");
		try {
			AerospikeData data = AerospikeData.forWrite(this.namespace);
			converter.write(objectToUpdate, data);
			data.setID(id);
			client.put(this.updatePolicy, data.getKey(), data.getBinsAsArray());
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
	}

	@Override
	public void delete(Class<?> type) {
		try {
			ScanPolicy scanPolicy = new ScanPolicy();
			scanPolicy.includeBinData = false;
			final AtomicLong count = new AtomicLong();
			client.scanAll(scanPolicy, namespace, type.getSimpleName(),
					new ScanCallback() {

						@Override
						public void scanCallback(Key key, Record record)
								throws AerospikeException {

							if (client.delete(null, key))
								count.addAndGet(1);
							/*
							 * after 25,000 records delete, return print the
							 * count.
							 */
							if (count.get() % 10000 == 0) {
								System.out.println("Deleted " + count.get());
							}

						}
					}, new String[] {});
			System.out.println("Deleted " + count + " records from set "
					+ type.getSimpleName());
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
	}

	@Override
	public <T> T delete(Serializable id, Class<T> type) {
		Assert.notNull(id, "Id must not be null!");
		try {
			AerospikeData data = AerospikeData.forWrite(this.namespace);
			data.setID(id);
			data.setSetName(AerospikeSimpleTypes.getColletionName(type));
			// converter.write(type, data);
			// data.setID(id);
			this.client.delete(null, data.getKey());
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
		return null;
	}

	@Override
	public <T> T delete(T objectToDelete) {
		Assert.notNull(objectToDelete, "Object to delete must not be null!");
		try {
			AerospikeData data = AerospikeData.forWrite(this.namespace);
			converter.write(objectToDelete, data);
			this.client.delete(null, data.getKey());
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
		return null;
	}

	@Override
	public <T> List<T> findAll(final Class<T> type) {

		// TODO returning a list is dangerous because
		// the list is unbounded and could contain billions of elements
		// we need to find another solution
		final List<T> scanList = new ArrayList<T>();
		Iterable<T> results = findAllUsingQuery(type, null, null);
		Iterator<T> iterator = results.iterator();
		try {
			while (iterator.hasNext()) {
				scanList.add(iterator.next());
			}
		}
		finally {
			((EntityIterator<T>) iterator).close();
		}
		return scanList;
	}

	@Override
	public <T> T findOne(Serializable id, Class<T> type) {
		Assert.notNull(id, "Id must not be null!");
		return findById(id, type);
	}

	@Override
	public <T> T findById(Serializable id, Class<T> type) {
		Assert.notNull(id, "Id must not be null!");
		try {
			AerospikePersistentEntity<?> entity = converter.getMappingContext()
					.getPersistentEntity(type);
			Key key = new Key(this.namespace, entity.getSetName(),
					id.toString());
			AerospikeData data = AerospikeData.forRead(key, null);
			Record record = this.client.get(null, key);
			data.setRecord(record);
			return converter.read(type, data);
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> Iterable<T> aggregate(Filter filter, Class<T> outputType,
			String module, String function, List<Value> arguments) {
		Assert.notNull(outputType, "Output type must not be null!");

		AerospikePersistentEntity<?> entity = converter.getMappingContext()
				.getPersistentEntity(outputType);

		Statement statement = new Statement();
		if (filter != null)
			statement.setFilters(filter);
		statement.setSetName(entity.getSetName());
		statement.setNamespace(this.namespace);
		ResultSet resultSet = null;
		if (arguments != null && arguments.size() > 0)
			resultSet = this.client.queryAggregate(null, statement, module,
					function, arguments.toArray(new Value[0]));
		else
			resultSet = this.client.queryAggregate(null, statement);
		return (Iterable<T>) resultSet;
	}

	/**
	 * Configures the {@link AerospikeExceptionTranslator} to be used.
	 * 
	 * @param exceptionTranslator can be {@literal null}.
	 */
	public void setExceptionTranslator(
			AerospikeExceptionTranslator exceptionTranslator) {
		this.exceptionTranslator = exceptionTranslator == null
				? DEFAULT_EXCEPTION_TRANSLATOR : exceptionTranslator;
	}

	@Override
	public String getSetName(Class<?> entityClass) {
		AerospikePersistentEntity<?> entity = converter.getMappingContext()
				.getPersistentEntity(entityClass);
		return entity.getSetName();
	}

	@Override
	public <T> Iterable<T> findAll(Sort sort, Class<T> type) {
		// TODO Auto-generated method stub
		return null;
	}

	private String resolveKeySpace(Class<?> type) {
		return this.mappingContext.getPersistentEntity(type).getSetName();
	}

	private static boolean typeCheck(Class<?> requiredType, Object candidate) {
		return candidate == null ? true
				: ClassUtils.isAssignable(requiredType, candidate.getClass());
	}

	public boolean exists(Query query, Class<?> entityClass) {

		if (query == null) {
			throw new InvalidDataAccessApiUsageException(
					"Query passed in to exist can't be null");
		}

		Iterator iterator = (Iterator<?>) find(query, entityClass).iterator();

		return iterator.hasNext();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.springframework.data.keyvalue.core.KeyValueOperations#execute(org.
	 * springframework.data.keyvalue.core.KeyValueCallback)
	 */
	@Override
	public <T> T execute(KeyValueCallback<T> action) {
		Assert.notNull(action, "KeyValueCallback must not be null!");

		try {
			return action.doInKeyValue(null);
		}
		catch (RuntimeException e) {
			throw e;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.springframework.data.aerospike.core.AerospikeOperations#count(org.
	 * springframework.data.aerospike.repository.query.Query, java.lang.Class)
	 */
	@SuppressWarnings("rawtypes")
	@Override
	public int count(Query<?> query, Class<?> type) {
		Assert.notNull(query, "Query must not be null!");
		Assert.notNull(type, "Type must not be null!");
		int i = 0;
		Iterator iterator = (Iterator<?>) find(query, type).iterator();
		for (; iterator.hasNext(); ++i)
			iterator.next();
		return i;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.springframework.data.aerospike.core.AerospikeOperations#find(org.
	 * springframework.data.aerospike.repository.query.Query, java.lang.Class)
	 */
	@Override
	public <T> Iterable<T> find(Query<?> query, Class<T> type) {
		Assert.notNull(query, "Query must not be null!");
		Assert.notNull(type, "Type must not be null!");
		List<Qualifier> qualifiers = null;
		Filter secondaryFilter = null;
		qualifiers = query.getQueryObject();
		if (qualifiers != null && qualifiers.size() > 0) {
			secondaryFilter = qualifiers.get(0).asFilter();
			if (secondaryFilter != null) {
				qualifiers.remove(0);
			}
		}

		final Iterable<T> results = findAllUsingQuery(type, secondaryFilter, qualifiers.toArray(new Qualifier[qualifiers.size()]));
		List<?> returnedList = IterableConverter.toList(results);
		if(results!=null && query.getSort()!=null){
			Comparator comparator = aerospikePropertyComparator(query);
			Collections.sort(returnedList, comparator);
		}
		return (Iterable<T>) returnedList;
	}
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public Comparator<?> aerospikePropertyComparator(Query<?> query ) {

		if (query == null || query.getSort() == null) {
			return null;
		}

		CompoundComparator compoundComperator = new CompoundComparator();
		for (Order order : query.getSort()) {

			if (Direction.DESC.equals(order.getDirection())) {
				compoundComperator.addComparator(new PropertyComparator(order.getProperty(), true, false));
			}else {
				compoundComperator.addComparator(new PropertyComparator(order.getProperty(), true, true));
			}
		}

		return compoundComperator;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.springframework.data.aerospike.core.AerospikeOperations#
	 * getMappingContext()
	 */
	@Override
	public MappingContext<?, ?> getMappingContext() {
		return this.mappingContext;
	}

	public String getNamespace() {
		return namespace;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.springframework.data.keyvalue.core.KeyValueOperations#findInRange(
	 * int, int, org.springframework.data.domain.Sort, java.lang.Class)
	 */
	@SuppressWarnings("rawtypes")
	@Override
	public <T> Iterable<T> findInRange(int offset, int rows, Sort sort,
			Class<T> type) {
		Assert.notNull(type, "Type for count must not be null!");
		final long rowCount = rows;
		final AtomicLong count = new AtomicLong(0);
		final Iterable<T> results = findAllUsingQuery(type, null, null);
		final Iterator<T> iterator = results.iterator();
		/*
		 * skip over offset
		 */

		for (int skip = 0; skip < offset; skip++) {
			if (iterator.hasNext())
				iterator.next();
		}
		/*
		 * setup the iterable litimed by 'rows'
		 */

		Iterable<T> returnList = new Iterable<T>() {

			@Override
			public Iterator<T> iterator() {
				return new Iterator<T>() {

					@Override
					public boolean hasNext() {
						if (count.get() == rowCount) {
							((EntityIterator<T>) iterator).close();
							return false;
						}
						else {
							return iterator.hasNext();
						}
					}

					@Override
					public T next() {
						if (count.addAndGet(1) <= rowCount) {
							return iterator.next();
						}
						else {
							return null;
						}
					}

					@Override
					public void remove() {

					}
				};
			}
		};
		return (Iterable<T>) returnList;// TODO:create a sort
	}

	@Override
	public long count(Class<?> type) {
		Assert.notNull(type, "Type for count must not be null!");
		AerospikePersistentEntity<?> entity = converter.getMappingContext()
				.getPersistentEntity(type);
		return count(type, entity.getSetName());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.springframework.data.keyvalue.core.KeyValueOperations#count(java.lang
	 * .Class)
	 */
	@Override
	public long count(Class<?> type, String setName) {
		Assert.notNull(type, "Type for count must not be null!");
		Node[] nodes = client.getNodes();
		int replicationCount = 2;
		int nodeCount = nodes.length;
		int n_objects = 0;
		for (Node node : nodes) {
			String infoString = Info.request(node,
					"sets/" + this.namespace + "/" + setName);
			String n_objectsString = infoString.substring(
					infoString.indexOf("=") + 1, infoString.indexOf(":"));
			n_objects = Integer.parseInt(n_objectsString);
		}

		return (nodeCount > 1) ? n_objects / replicationCount : n_objects;
	}

	protected <T> Iterable<T> findAllUsingQuery(Class<T> type, Filter filter, Qualifier... qualifiers) {
		final Class<T> classType = type;
		Statement stmt = new Statement();
		stmt.setNamespace(this.namespace);
		stmt.setSetName(this.getSetName(type));
		Iterable<T> results = null;

		final KeyRecordIterator recIterator = this.queryEngine.select(
				this.namespace, this.getSetName(type), filter, qualifiers);

		results = new Iterable<T>() {

			@Override
			public Iterator<T> iterator() {
				return new EntityIterator<T>(classType, converter, recIterator);
			}

		};
		return results;
	}


	public class EntityIterator<T> implements CloseableIterator<T> {

		private KeyRecordIterator keyRecordIterator;

		private MappingAerospikeConverter converter;

		private Class<T> type;
		
		public EntityIterator(Class<T> type,
				MappingAerospikeConverter converter,
				KeyRecordIterator keyRecordIterator) {
			this.converter = converter;
			this.type = type;
			this.keyRecordIterator = keyRecordIterator;
		}

		@Override
		public boolean hasNext() {
			return this.keyRecordIterator.hasNext();
		}

		@Override
		public T next() {
			KeyRecord keyRecord = this.keyRecordIterator.next();
			AerospikeData data = AerospikeData.forRead(keyRecord.key, null);
			data.setRecord(keyRecord.record);
			return converter.read(type, data);
		}

		@Override
		public void close() {
			try {
				keyRecordIterator.close();
			}
			catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		@Override
		public void remove() {

		}
	}

	@Override
	public <T> T prepend(T objectToPrependTo, String fieldName, String value) {
		Assert.notNull(objectToPrependTo,
				"Object to prepend to must not be null!");
		T result = null;
		try {

			AerospikeData data = AerospikeData.forWrite(this.namespace);
			converter.write(objectToPrependTo, data);
			Record record = this.client.operate(null, data.getKey(),
					Operation.prepend(new Bin(fieldName, value)),
					Operation.get(fieldName));
			data.setRecord(record);
			result = (T) converter.read(objectToPrependTo, data);
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
		return result;
	}

	@Override
	public <T> T prepend(T objectToPrependTo, Map<String, String> values) {
		Assert.notNull(objectToPrependTo,
				"Object to prepend to must not be null!");
		T result = null;
		try {
			AerospikeData data = AerospikeData.forWrite(this.namespace);
			converter.write(objectToPrependTo, data);
			Operation[] ops = new Operation[values.size() + 1];
			int x = 0;
			for (Map.Entry<String, String> entry : values.entrySet()) {
				Bin newBin = new Bin(entry.getKey(), entry.getValue());
				ops[x] = Operation.prepend(newBin);
				x++;
			}
			ops[x] = Operation.get();
			Record record = this.client.operate(null, data.getKey(), ops);
			data.setRecord(record);
			result = (T) converter.read(objectToPrependTo, data);
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
		return result;
	}

	@Override
	public <T> T append(T objectToAppendTo, Map<String, String> values) {
		Assert.notNull(objectToAppendTo,
				"Object to append to must not be null!");
		T result = null;
		try {
			AerospikeData data = AerospikeData.forWrite(this.namespace);
			converter.write(objectToAppendTo, data);
			Operation[] ops = new Operation[values.size() + 1];
			int x = 0;
			for (Map.Entry<String, String> entry : values.entrySet()) {
				Bin newBin = new Bin(entry.getKey(), entry.getValue());
				ops[x] = Operation.append(newBin);
				x++;
			}
			ops[x] = Operation.get();
			Record record = this.client.operate(null, data.getKey(), ops);
			data.setRecord(record);
			result = (T) converter.read(objectToAppendTo, data);
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
		return result;
	}

	@Override
	public <T> T append(T objectToAppendTo, String binName, String value) {
		Assert.notNull(objectToAppendTo,
				"Object to append to must not be null!");
		T result = null;
		try {

			AerospikeData data = AerospikeData.forWrite(this.namespace);
			converter.write(objectToAppendTo, data);
			Record record = this.client.operate(null, data.getKey(),
					Operation.append(new Bin(binName, value)),
					Operation.get(binName));
			data.setRecord(record);
			result = (T) converter.read(objectToAppendTo, data);
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
		return result;
	}

	public <T> T add(T objectToAddTo, Map<String, Long> values) {
		Assert.notNull(objectToAddTo, "Object to add to must not be null!");
		T result = null;
		try {

			AerospikeData data = AerospikeData.forWrite(this.namespace);
			converter.write(objectToAddTo, data);
			Operation[] operations = new Operation[values.size() + 1];
			int x = 0;
			for (Map.Entry<String, Long> entry : values.entrySet()) {
				Bin newBin = new Bin(entry.getKey(), entry.getValue());
				operations[x] = Operation.add(newBin);
				x++;
			}
			operations[x] = Operation.get();
			Record record = this.client.operate(null, data.getKey(),
					operations);
			data.setRecord(record);
			result = (T) converter.read(objectToAddTo, data);

		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
		return result;
	}

	public <T> T add(T objectToAddTo, String binName, int value) {
		Assert.notNull(objectToAddTo, "Object to add to must not be null!");
		T result = null;
		try {

			AerospikeData data = AerospikeData.forWrite(this.namespace);
			converter.write(objectToAddTo, data);
			Record record = this.client.operate(null, data.getKey(),
					Operation.add(new Bin(binName, value)), Operation.get());
			data.setRecord(record);
			result = (T) converter.read(objectToAddTo, data);
		}
		catch (AerospikeException o_O) {
			DataAccessException translatedException = exceptionTranslator
					.translateExceptionIfPossible(o_O);
			throw translatedException == null ? o_O : translatedException;
		}
		return result;
	}

}
