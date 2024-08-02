package com.grswebservices.peopledb.repository;

import com.grswebservices.peopledb.exception.UnableToSaveException;
import com.grswebservices.peopledb.model.Person;

import java.sql.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

public class PeopleRepository {
    public static final String SAVE_PERSON_SQL = "INSERT INTO PEOPLE (FIRST_NAME, LAST_NAME, DOB) VALUES(?, ?, ?)";
    private final Connection connection;

    // can't create a PeopleRepository without passing in a connection
    public PeopleRepository(Connection connection) {
        this.connection = connection;
    }

    // prepareStatement gives us more elegant way of passing values in rather than createStatement
    public Person save(Person person) throws UnableToSaveException {
        try {
            // SQL injection - not possible with prepareStatement, however is possible if we had used string concatenation
            // in a prepare statement every parameter we are expecting in is constrained to its little bit of data
            // the rule to follow when writing JDBC code is, if you've ot parameters that you need to bind to outside data: use a preparedStatement as that will save you from this particular level of SQL injection
            // prepared statements are pre-compiled, so they execute more quick;y than regular Statements. Prefer PreparedStatement.
            PreparedStatement ps = connection.prepareStatement(SAVE_PERSON_SQL, PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, person.getFirstName());
            ps.setString(2, person.getLastName());
            // standardize to GMT-0
            // dob returns a zonedDateTime and whatever the timezone was that we specified when we created it
            // then we're translating from that timezone to GMT-0 withZoneSameInstant method
            // then translating that zonedDateTime into a localDateTime which we can then pass into the Timestamp.valueOf() method
            ps.setTimestamp(3, Timestamp.valueOf(person.getDob().withZoneSameInstant(ZoneId.of("+0")).toLocalDateTime()));
            int recordsAffected = ps.executeUpdate();
            // think of ResultSet as a 2-dimensional array, rows and columns
            ResultSet rs = ps.getGeneratedKeys();
            while (rs.next()) {
                long id = rs.getLong(1);
                person.setId(id);
                System.out.println(person);
            }
            System.out.printf("Records affected: %d%n", recordsAffected);
        } catch (SQLException e) {
            e.printStackTrace();
            throw new UnableToSaveException("Tried to save person: " + person);
        }
        return person;
    }

    public Person findById(Long id) {
        Person person = null;

        try {
            PreparedStatement ps = connection.prepareStatement("SELECT ID, FIRST_NAME, LAST_NAME, DOB FROM PEOPLE WHERE ID=?");
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            // telling rs to go to next line or next row
            while (rs.next()) {
                long personId = rs.getLong("ID");
                String firstName = rs.getString("FIRST_NAME");
                String lastName = rs.getString("LAST_NAME");
                // When we create an instance of Person we can specify the TimeZone for DOB and it can be any TZ in the world, but when we get them from the DB they are all going to be normalized to a TZ of GMT+0
                // I can't know what TZ this person was born, I just know that the moment in time in the world where they were born is whatever it is relative to GMT+0
                ZonedDateTime dob = ZonedDateTime.of(rs.getTimestamp("DOB").toLocalDateTime(), ZoneId.of("+0"));
                person = new Person(firstName, lastName, dob);
                person.setId(personId);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return person;
    }
}
