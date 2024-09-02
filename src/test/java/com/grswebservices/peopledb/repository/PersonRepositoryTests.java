package com.grswebservices.peopledb.repository;

import com.grswebservices.peopledb.model.Address;
import com.grswebservices.peopledb.model.Person;
import com.grswebservices.peopledb.model.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Need the tests to fail before we implement anything, otherwise we can't be sure that we've actually implemented the needed functionality
// main thing is make sure you have a failing test before you implement any code, then you run the test again, hopefully it passes, then you can mostly trust that you're probably on the right path.
public class PersonRepositoryTests {

    private Connection connection;
    private PersonRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection("jdbc:h2:~\\Documents\\Database\\DBeaver\\peopletestdb".replace("~", System.getProperty("user.home")));
        // we can run tests without additional records showing up in database - or at least they are wiped out afterwards
        connection.setAutoCommit(false);
        repo = new PersonRepository(connection);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    public void canSaveOnePerson() throws SQLException {
        Person john = new Person("John", "Smith", ZonedDateTime.of(1980, 11, 15, 15, 15, 0, 0, ZoneId.of("-6")));
        Person savedPerson = repo.save(john);
        assertThat(savedPerson.getId()).isGreaterThan(0);
    }

    @Test
    public void canSaveTwoPeople() {
        Person john = new Person("John", "Smith", ZonedDateTime.of(1980, 11, 15, 15, 15, 0, 0, ZoneId.of("-6")));
        Person bobby = new Person("Bobby", "Smith", ZonedDateTime.of(1982, 11, 15, 15, 15, 0, 0, ZoneId.of("-6")));
        Person savedPerson1 = repo.save(john);
        Person savedPerson2 = repo.save(bobby);
        assertThat(savedPerson1.getId()).isNotEqualTo(savedPerson2.getId());
    }

    @Test
    public void canSavePersonWithAddress() throws SQLException {
        Person john = new Person("JohnZZZ", "Smith", ZonedDateTime.of(1980, 11, 15, 15, 15, 0, 0, ZoneId.of("-6")));
        Address address = new Address(null, "123 Beale St.", "Apt. 1A", "Wala Wala", "WA", "90210", "Fulton County", Region.WEST, "United States");
        john.setHomeAddress(address);

        Person savedPerson = repo.save(john);
        assertThat(savedPerson.getHomeAddress().get().id()).isGreaterThan(0);
        connection.commit();
    }

    @Test
    public void canFindPersonById() {
        // create a person, save them into the db, then turn around and try to find that person by their id
        Person savedPerson = repo.save(new Person("test", "jackson", ZonedDateTime.now()));
        Person foundPerson = repo.findById(savedPerson.getId()).get();
        // equals() method = all properties (id, name, dob, ...) of entities compared have to be equal
        assertThat(foundPerson).isEqualTo(savedPerson);
    }

    @Test
    public void testPersonIdNotFound() {
        // db doesn't generate negative Ids so we won't be able to find -1L
         Optional<Person> foundPerson = repo.findById(-1L);
         assertThat(foundPerson).isEmpty();
    }

    @Test
    public void canGetCount() throws SQLException {
        long startCount = repo.count();
        repo.save(new Person("John1", "Smith", ZonedDateTime.of(1980, 11, 15, 15, 15, 0, 0, ZoneId.of("-6"))));
        repo.save(new Person("John2", "Smith", ZonedDateTime.of(1980, 11, 15, 15, 15, 0, 0, ZoneId.of("-6"))));
        long endCount = repo.count();
        assertThat(endCount).isEqualTo(startCount + 2);
    }

    @Test
    public void canDelete() throws SQLException {
        Person savedPerson = repo.save(new Person("John1", "Smith", ZonedDateTime.of(1980, 11, 15, 15, 15, 0, 0, ZoneId.of("-6"))));
        long startCount = repo.count();
        repo.delete(savedPerson);
        long endCount = repo.count();
        assertThat(endCount).isEqualTo(startCount - 1);
    }

    @Test
    public void canDeleteMultiplePeople() {
        Person p1 = repo.save(new Person("John1", "Smith", ZonedDateTime.of(1980, 11, 15, 15, 15, 0, 0, ZoneId.of("-6"))));
        Person p2 = repo.save(new Person("John2", "Smith", ZonedDateTime.of(1980, 11, 15, 15, 15, 0, 0, ZoneId.of("-6"))));
        long startCount = repo.count();
        repo.delete(p1, p2);
        long endCount = repo.count();
        assertThat(endCount).isEqualTo(startCount - 2);
    }

    @Test
    public void canUpdate() {
        // create a person, put that person into the db, retrieve that person, modify that person object, then create a new update method passing in that new modified person into the db, then retrieve that person by their id again from the db, then we will have the 2 person objects in our code, and then we can aseert that whatever field we changed is actually differnet between those two people
        Person savedPerson = repo.save(new Person("John1", "Smith", ZonedDateTime.of(1980, 11, 15, 15, 15, 0, 0, ZoneId.of("-6"))));

        Person p1 = repo.findById(savedPerson.getId()).get();

        savedPerson.setSalary(new BigDecimal("73000.28"));
        repo.update(savedPerson);

        Person p2 = repo.findById(savedPerson.getId()).get();

        assertThat(p2.getSalary()).isNotEqualTo(p1.getSalary());
    }

    @Test
    @Disabled
    // CAREFUL - DO NOT RUN THIS MORE THAN ONCE
    public void loadData() throws IOException, SQLException {
        Files.lines(Path.of("~\\Documents\\Java Course Files\\Big Data\\Hr5m.csv".replace("~", System.getProperty("user.home"))))
                .skip(1)
//                .limit(100)
                .map(l -> l.split(","))
                .map(a -> {
                    LocalDate dob = LocalDate.parse(a[10], DateTimeFormatter.ofPattern("M/d/yyyy"));
                    LocalTime tob = LocalTime.parse(a[11], DateTimeFormatter.ofPattern("hh:mm:ss a").withLocale(Locale.US));
                    LocalDateTime dtob = LocalDateTime.of(dob, tob);
                    ZonedDateTime zdtob = ZonedDateTime.of(dtob, ZoneId.of("+0"));
                    Person person = new Person(a[2], a[4], zdtob);
                    person.setSalary(new BigDecimal(a[25]));
                    person.setEmail(a[6]);
                    return person;
                })
                .forEach(repo::save);
        connection.commit(); // in this test case we want to commit data to db
    }

    ////////////////////////////////////////////
    // EXPERIMENTS
    ////////////////////////////////////////////
    // a good technique to try something out quickly - almost equivalent to a implementing a psvm method, however in frameworks the psvm approach doesn't really apply anymore so you need a place to try things out
    @Test
    public void experiment() {
        Person p1 = new Person(10L, null, null, null);
        Person p2 = new Person(20L, null, null, null);
        Person p3 = new Person(30L, null, null, null);
        Person p4 = new Person(40L, null, null, null);
        Person p5 = new Person(50L, null, null, null);

        // DELETE FROM PERSON WHERE ID IN (10,20,30,40,50);

        Person[] people = Arrays.asList(p1, p2, p3, p4, p5).toArray(new Person[]{});

        String ids = Arrays.stream(people)
                .map(Person::getId) // convert stream of people into a stream of ids
                .map(String::valueOf) // convert stream of Long ids into a stream of Text ids - equivalent to String.valueOf(20L)
                .collect(Collectors.joining(","));// collect all string ids together and put a comma between them = comma delimited.
        System.out.println(ids);
    }
}
