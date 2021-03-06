package org.springframework.data.aerospike.repository.support;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.data.aerospike.core.AerospikeOperations;
import org.springframework.data.keyvalue.core.IterableConverter;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.core.support.PersistentEntityInformation;
import org.springframework.util.Assert;
import org.springframework.data.aerospike.repository.AerospikeRepository;
import org.springframework.data.aerospike.repository.query.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.aerospike.client.query.IndexType;

public class SimpleAerospikeRepository<T, ID extends Serializable> implements AerospikeRepository<T, ID> {

	private final AerospikeOperations operations;
	private final EntityInformation<T, ID> entityInformation;

	
	public SimpleAerospikeRepository(EntityInformation<T, ID> metadata,
			AerospikeOperations operations) {
		this.entityInformation = metadata;
		this.operations = (AerospikeOperations) operations;
	}
	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#findOne(java.io.Serializable)
	 */
	@Override
	public T findOne(ID id) {
		return operations.findById(id, entityInformation.getJavaType(), getDomainClass());
	}
	@Override
	public <S extends T> S save(S entity) {
		Assert.notNull(entity);
		operations.save(entityInformation.getId(entity), entity, getDomainClass());
		return entity;
	}
	
	public <S extends T> List<S> save(Iterable<S> entities) {
		
		Assert.notNull(entities, "The given Iterable of entities not be null!");
		
		List<S> result = convertIterableToList(entities);
		boolean allNew = true;
		for (S entity : result) {
			save(entity);
		}
		
		return result;
		
	}
	
	@Override
	public void delete(T entity) {
		operations.delete(entity);
	}

	/**
	 * @return the entityInformation
	 */
	public Class<T> getDomainClass() {
		return  this.entityInformation.getJavaType();
	}
	
	static <T> List<T> convertIterableToList(Iterable<T> entities) {

		if (entities instanceof List) {
			return (List<T>) entities;
		}

		int capacity = tryDetermineRealSizeOrReturn(entities, 10);

		if (capacity == 0 || entities == null) {
			return Collections.<T> emptyList();
		}

		List<T> list = new ArrayList<T>(capacity);
		for (T entity : entities) {
			list.add(entity);
		}

		return list;
	}

	private static int tryDetermineRealSizeOrReturn(Iterable<?> iterable, int defaultSize) {
		return iterable == null ? 0 : (iterable instanceof Collection) ? ((Collection<?>) iterable).size() : defaultSize;
	}
	/* (non-Javadoc)
	 * @see org.springframework.data.repository.PagingAndSortingRepository#findAll(org.springframework.data.domain.Sort)
	 */
	@Override
	public Iterable<T> findAll(Sort sort) {
		return operations.findAll(sort, entityInformation.getJavaType());
	}
	/* (non-Javadoc)
	 * @see org.springframework.data.repository.PagingAndSortingRepository#findAll(org.springframework.data.domain.Pageable)
	 */
	@Override
	public Page<T> findAll(Pageable pageable) {

		if (pageable == null) {
			List<T> result = findAll();
			return new PageImpl<T>(result, null, result.size());
		}

		Iterable<T> content = operations.findInRange(pageable.getOffset(), pageable.getPageSize(), pageable.getSort(),entityInformation.getJavaType());

		return new PageImpl<T>(IterableConverter.toList(content), pageable, this.operations.count(entityInformation.getJavaType(),getDomainClass().getSimpleName()));
	}
	/* (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#exists(java.io.Serializable)
	 */
	@Override
	public boolean exists(ID id) {
		return findOne(id) != null;
	}
	/* (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#findAll()
	 */

	@Override
	public List<T> findAll() {
		return IterableConverter.toList(operations.findAll(entityInformation.getJavaType()));
	}
	/* (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#findAll(java.lang.Iterable)
	 */
	@Override
	public Iterable<T> findAll(Iterable<ID> ids) {
		List<T> result = new ArrayList<T>();

		for (ID id : ids) {

			T candidate = findOne(id);

			if (candidate != null) {
				result.add(candidate);
			}
		}

		return result;
	}
	/* (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#count()
	 */
	@Override
	public long count() {
		return operations.count(entityInformation.getJavaType());
		//return 0;
	}
	/* (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#delete(java.io.Serializable)
	 */
	@Override
	public void delete(ID id) {
		Assert.notNull(id, "The given id must not be null!");
		operations.delete(id, entityInformation.getJavaType());
		
	}
	/* (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#delete(java.lang.Iterable)
	 */
	@Override
	public void delete(Iterable<? extends T> entities) {
		for (T entity : entities) {
			delete(entity);
		}
		
	}
	/* (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#deleteAll()
	 */
	@Override
	public void deleteAll() {
		operations.delete(entityInformation.getJavaType());
		
	}
	/* (non-Javadoc)
	 * @see org.springframework.data.aerospike.repository.AerospikeRepository#createIndex(java.lang.Class, java.lang.String, java.lang.String, com.aerospike.client.query.IndexType)
	 */
	@Override
	public <T> void createIndex(Class<T> domainType, String indexName,String binName, IndexType indexType) {
		operations.createIndex(domainType, indexName, binName, indexType);
	}
	
}
