package com.grswebservices.peopledb.repository;

import com.grswebservices.peopledb.exception.UnableToSaveException;
import com.grswebservices.peopledb.model.Entity;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

abstract public class CRUDRepository<T extends Entity> {

    // protected so that it can be seen by subclasses like PeopleRepository
    protected Connection connection;

    public CRUDRepository(Connection connection) {
        this.connection = connection;
    }

    public T save(T entity) {
        try {
            PreparedStatement ps = connection.prepareStatement(getSaveSql(), PreparedStatement.RETURN_GENERATED_KEYS);
            mapForSave(entity, ps);
            int recordsAffected = ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            while (rs.next()) {
                long id = rs.getLong(1);
                entity.setId(id);
                System.out.println(entity);
            }
            System.out.printf("Records affected: %d%n", recordsAffected);
        } catch (SQLException e) {
            e.printStackTrace();
            throw new UnableToSaveException("Tried to save entity: " + entity);
        }
        return entity;
    }

    /**
     * @return Returns a String that represents the SQL needed to retrieve one entity.
     * The SQL must contain one SQL parameter, i.e. "?", that will bind to the
     * entity's ID.
     */
    public Optional<T> findById(Long id) {
        T entity = null;

        try {
            PreparedStatement ps = connection.prepareStatement(getFindByIdSql());
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            // telling rs to go to next line or next row
            while (rs.next()) {
                entity = extractEntityFromResultSet(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        // if we didn't get any result from rs then the returned entity will be null so Optional.of would blow up - hence has to be Optional.ofNullable
        return Optional.ofNullable(entity);
    }

    private List<T> findAll() {
        List<T> entities = new ArrayList<>();
        try {
            PreparedStatement ps = connection.prepareStatement(getFindAllSql());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                entities.add(extractEntityFromResultSet(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return entities;
    }

    public long count() {
        long count = 0;
        try {
            PreparedStatement ps = connection.prepareStatement(getCountSql());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                count = rs.getLong(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return count;
    }

    public void delete(T entity) {
        try {
            PreparedStatement ps = connection.prepareStatement(getDeleteSql());
            ps.setLong(1, entity.getId());
            int affectedRecordCount = ps.executeUpdate(); // can pass in sql directly into ps.executeUpdate() however it would not be as beneficial as defining our sql when we create our prepared statement because then that sql has the opportunity to be precompiled.
            System.out.println(affectedRecordCount);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // var args are basically a shorthand for passing in an array: People...entities = People[] entities - except that we don't have to create an array in the code that calls this method
    // so it makes it pretty easy to call this method and just pass in any arbitrary number of People objects
    public void delete(T...entities) {
        // Option 1:
        // for (Person person: entities) {
            // delete(person); // delegate to delete method - however there is a more efficient way. With this way we would be making separate and distinct updates to the database for each of the entities that are passed in here. There is a more efficient way with one call to the database.
        // }
        // Option 2:
        // advantage of this approach is that versus the for loop above when we were just delegating down to the original delete method, is that we are now able to have the db delete multiple records from the table simultaneously, which is much more efficient, especially if lots of records need to be updated.
        // current version of H2 don't support using a preparedStatement for this operation
        try {
            Statement stmt = connection.createStatement();
            String ids = Arrays.stream(entities)
                    .map(T::getId) // convert stream of entities into a stream of ids
                    .map(String::valueOf) // convert stream of Long ids into a stream of Text ids - equivalent to String.valueOf(20L)
                    .collect(Collectors.joining(","));// collect all string ids together and put a comma between them = comma delimited.
            // "DELETE FROM PEOPLE WHERE ID IN (?,?,?,?)" - generate these in clause parameters dynamically // In clause limit for: Oracle = 1000, MSSQL = 2100, PostgreSQL = >32,767, H2 = ?
            int affectedRecordCount = stmt.executeUpdate(getDeleteInSql().replace(":ids", ids));// (:id)= "sql named parameter" - although h2 doesn't support this so this is a fake or poor man's version
            System.out.println(affectedRecordCount);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void update(T entity) {
        try {
            PreparedStatement ps = connection.prepareStatement(getUpdateSql());
            mapForUpdate(entity, ps);
            ps.setLong(5, entity.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // abstract methods: subclasses must implement that method. Not implemented on the defining class.

    protected abstract String getSaveSql();

    protected abstract String getCountSql();

    protected abstract String getFindAllSql();

    protected abstract String getFindByIdSql();

    protected abstract String getDeleteSql();

    /**
     *
     * @return Should return a SQL string like:
     * "DELETE FROM PEOPLE WHERE ID IN (:ids)"
     * Be sure to include the '(:ids)' named parameter and call it 'ids'
     */
    protected abstract String getDeleteInSql();

    protected abstract String getUpdateSql();

    abstract T extractEntityFromResultSet(ResultSet rs) throws SQLException;

    abstract void mapForSave(T entity, PreparedStatement ps) throws SQLException;

    abstract void mapForUpdate(T entity, PreparedStatement ps) throws SQLException;
}
