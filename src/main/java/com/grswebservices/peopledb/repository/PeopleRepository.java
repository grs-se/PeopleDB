package com.grswebservices.peopledb.repository;

import com.grswebservices.peopledb.exception.UnableToSaveException;
import com.grswebservices.peopledb.model.Person;

import java.sql.*;
import java.time.ZoneId;
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
        return new Person("" ,"", null);
    }
}
