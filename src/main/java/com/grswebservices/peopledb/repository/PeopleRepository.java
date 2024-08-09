package com.grswebservices.peopledb.repository;

import com.grswebservices.peopledb.exception.UnableToSaveException;
import com.grswebservices.peopledb.model.Person;

import java.math.BigDecimal;
import java.sql.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class PeopleRepository extends CRUDRepository<Person> {
    public static final String SAVE_PERSON_SQL = "INSERT INTO PEOPLE (FIRST_NAME, LAST_NAME, DOB) VALUES(?, ?, ?)";
    public static final String FIND_BY_ID_SQL = "SELECT ID, FIRST_NAME, LAST_NAME, DOB, SALARY FROM PEOPLE WHERE ID=?";
    public static final String FIND_ALL_SQL = "SELECT ID, FIRST_NAME, LAST_NAME, DOB, SALARY FROM PEOPLE";

    public PeopleRepository(Connection connection) {
        super(connection); // because now the PeopleRepo is extending the CRUDRepo, this classes constructor has to call the super constructor
        // this.connection = connection; // PeopleRepo now inheriting connection from CRUDRepo
    }

    @Override
    String getSavedSql() {
        return SAVE_PERSON_SQL;
    }

    @Override
    void mapForSave(Person entity, PreparedStatement ps) throws SQLException {
        ps.setString(1, entity.getFirstName());
        ps.setString(2, entity.getLastName());
        ps.setTimestamp(3, convertDobToTimestamp(entity.getDob()));
    }

    @Override
    Person extractEntityFromResultSet(ResultSet rs) throws SQLException {
        // In professional environmental a framework may automatically take care of these String literals to make sure that they are always in sync with whatever is in the database
        long personId = rs.getLong("ID");
        String firstName = rs.getString("FIRST_NAME");
        String lastName = rs.getString("LAST_NAME");
        // When we create an instance of Person we can specify the TimeZone for DOB and it can be any TZ in the world, but when we get them from the DB they are all going to be normalized to a TZ of GMT+0
        // I can't know what TZ this person was born, I just know that the moment in time in the world where they were born is whatever it is relative to GMT+0
        ZonedDateTime dob = ZonedDateTime.of(rs.getTimestamp("DOB").toLocalDateTime(), ZoneId.of("+0"));
        BigDecimal salary = rs.getBigDecimal("SALARY");
        return new Person(personId, firstName, lastName, dob, salary);
    }

    @Override
    protected String getFindByIdSql() {
        return FIND_BY_ID_SQL;
    }

    private List<Person> findAll() {
        List<Person> people = new ArrayList<>();
        try {
            PreparedStatement ps = connection.prepareStatement(FIND_ALL_SQL);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                people.add(extractEntityFromResultSet(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return people;
    }

    public long count() {
        long count = 0;
        try {
            PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM PEOPLE");
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                count = rs.getLong(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return count;
    }

    public void delete(Person savedPerson) {
        try {
            PreparedStatement ps = connection.prepareStatement("DELETE FROM PEOPLE WHERE ID=?");
            ps.setLong(1, savedPerson.getId());
            int affectedRecordCount = ps.executeUpdate(); // can pass in sql directly into ps.executeUpdate() however it would not be as beneficial as defining our sql when we create our prepared statement because then that sql has the opportunity to be precompiled.
            System.out.println(affectedRecordCount);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // var args are basically a shorthand for passing in an array: People...people = People[] people - except that we don't have to create an array in the code that calls this method
    // so it makes it pretty easy to call this method and just pass in any arbitrary number of People objects
    public void delete(Person...people) {
        // Option 1:
//        for (Person person: people) {
//            delete(person); // delegate to delete method - however there is a more efficient way. With this way we would be making separate and distinct updates to the database for each of the people that are passed in here. There is a more efficient way with one call to the database.
//        }
        // Option 2:
        // advantage of this approach is that versus the for loop above when we were just delegating down to the original delete method, is that we are now able to have the db delete multiple records from the table simultaneously, which is much more efficient, especially if lots of records need to be updated.
        // current version of H2 don't support using a preparedStatement for this operation
        try {
            Statement stmt = connection.createStatement();
            String ids = Arrays.stream(people)
                    .map(Person::getId) // convert stream of people into a stream of ids
                    .map(String::valueOf) // convert stream of Long ids into a stream of Text ids - equivalent to String.valueOf(20L)
                    .collect(Collectors.joining(","));// collect all string ids together and put a comma between them = comma delimited.
            // "DELETE FROM PEOPLE WHERE ID IN (?,?,?,?)" - generate these in clause parameters dynamically // In clause limit for: Oracle = 1000, MSSQL = 2100, PostgreSQL = >32,767, H2 = ?
            int affectedRecordCount = stmt.executeUpdate("DELETE FROM PEOPLE WHERE ID IN (:ids)".replace(":ids", ids));// (:id)= "sql named parameter" - although h2 doesn't support this so this is a fake or poor man's version
            System.out.println(affectedRecordCount);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }

    public void update(Person person) {
        try {
            PreparedStatement ps = connection.prepareStatement("UPDATE PEOPLE SET FIRST_NAME=?, LAST_NAME=?, DOB=?, SALARY=? WHERE ID=?");
            ps.setString(1, person.getFirstName());
            ps.setString(2, person.getLastName());
            ps.setTimestamp(3, convertDobToTimestamp(person.getDob()));
            ps.setBigDecimal(4, person.getSalary());
            ps.setLong(5, person.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Timestamp convertDobToTimestamp(ZonedDateTime dob) {
        return Timestamp.valueOf(dob.withZoneSameInstant(ZoneId.of("+0")).toLocalDateTime());
    }
}
