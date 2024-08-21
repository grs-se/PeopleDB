[## DATABASES
]()
```java
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

public class PeopleRepository {
    public static final String SAVE_PERSON_SQL = "INSERT INTO PEOPLE (FIRST_NAME, LAST_NAME, DOB) VALUES(?, ?, ?)";
    public static final String FIND_BY_ID_SQL = "SELECT ID, FIRST_NAME, LAST_NAME, DOB, SALARY FROM PEOPLE WHERE ID=?";
    public static final String FIND_ALL_SQL = "SELECT ID, FIRST_NAME, LAST_NAME, DOB, SALARY FROM PEOPLE";
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
            ps.setTimestamp(3, convertDobToTimestamp(person.getDob()));
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

    public Optional<Person> findById(Long id) {
        Person person = null;

        try {
            PreparedStatement ps = connection.prepareStatement(FIND_BY_ID_SQL);
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            // telling rs to go to next line or next row
            while (rs.next()) {
                person = extractPersonFromResultSet(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        // if we didn't get any result from rs then the returned person will be null so Optional.of would blow up - hence has to be Optional.ofNullable
        return Optional.ofNullable(person);
    }

    private List<Person> findAll() {
        List<Person> people = new ArrayList<>();
        try {
            PreparedStatement ps = connection.prepareStatement(FIND_ALL_SQL);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                people.add(extractPersonFromResultSet(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return people;
    }

    private static Person extractPersonFromResultSet(ResultSet rs) throws SQLException {
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
```


- this is all it takes now to generate a save method - a method that generates the SQL and a method that does the mapping.
- all tests still pass
- a resueable generic CRUD repository class that will help us to create additional repositories 

```java
public class PeopleRepository extends CRUDRepository<Person> {
    public static final String SAVE_PERSON_SQL = "INSERT INTO PEOPLE (FIRST_NAME, LAST_NAME, DOB) VALUES(?, ?, ?)";

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
```
- the whole point of OOP is to model the real world, not to model computer science concepts. So naming conventions prefer "entities" (plural is enough) rather than "entitiesList"
- TDD: when refactoring not necessary to do TDD as you write tests for new fucntionality. Refactoring is technically not new fucntionality. Refactoring is really just reshuffling existing functionality in some other way. So as long as we're not introducing any new functionality we don't need to start with a new test. And in fact, because I have a suite of tests for the existing functionality, tjhat's what's giving me guard rails to feel safe about doing this level of surgery pm this class - moving a little bit at a time, make a few changes just to make that new method work, and then use tests to confirm whether i broke any existing functiaopnlity, and if- i had broke existing functionality that would be called regression, so this is called regression testing . I dont try to reimplenment every single class all in one foul swoop.

---

- Look how much cleaner this now is. This is where we get into the 'craftsmanship' of writing code. 
- Generics, abstract classes, super classes, sub classes, cleaning up the messiness of try catch, and now it's so much more concise, and now moore importantly we've set ourselves up to create another repository class much more easily and quickly than we had before, just focussing ont he most important bits, and now all the other scaffolding will already be taken care of/
- This is one of the types of exercises where you can start to really get an appreciation for OOP, that's not to say that you couldn't do similarly nice clean up activities with another programming paradigm, but this is some of how you can do this with OOP. 
```java
package com.grswebservices.peopledb.repository;

import com.grswebservices.peopledb.model.Person;

import java.math.BigDecimal;
import java.sql.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class PeopleRepository extends CRUDRepository<Person> {
    public static final String SAVE_PERSON_SQL = "INSERT INTO PEOPLE (FIRST_NAME, LAST_NAME, DOB) VALUES(?, ?, ?)";
    public static final String FIND_BY_ID_SQL = "SELECT ID, FIRST_NAME, LAST_NAME, DOB, SALARY FROM PEOPLE WHERE ID=?";
    public static final String FIND_ALL_SQL = "SELECT ID, FIRST_NAME, LAST_NAME, DOB, SALARY FROM PEOPLE";
    public static final String SELECT_COUNT_SQL = "SELECT COUNT(*) FROM PEOPLE";
    public static final String DELETE_SQL = "DELETE FROM PEOPLE WHERE ID=?";
    public static final String DELETE_IN_SQL = "DELETE FROM PEOPLE WHERE ID IN (:ids)";
    public static final String UPDATE_SQL = "UPDATE PEOPLE SET FIRST_NAME=?, LAST_NAME=?, DOB=?, SALARY=? WHERE ID=?";

    public PeopleRepository(Connection connection) {
        super(connection);
    }

    @Override
    protected String getSaveSql() {
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
        long personId = rs.getLong("ID");
        String firstName = rs.getString("FIRST_NAME");
        String lastName = rs.getString("LAST_NAME");
        ZonedDateTime dob = ZonedDateTime.of(rs.getTimestamp("DOB").toLocalDateTime(), ZoneId.of("+0"));
        BigDecimal salary = rs.getBigDecimal("SALARY");
        return new Person(personId, firstName, lastName, dob, salary);
    }

    @Override
    void mapForUpdate(Person entity, PreparedStatement ps) throws SQLException {
        ps.setString(1, entity.getFirstName());
        ps.setString(2, entity.getLastName());
        ps.setTimestamp(3, convertDobToTimestamp(entity.getDob()));
        ps.setBigDecimal(4, entity.getSalary());
    }

    @Override
    protected String getFindByIdSql() {
        return FIND_BY_ID_SQL;
    }

    @Override
    protected String getFindAllSql() {
        return FIND_ALL_SQL;
    }

    @Override
    protected String getCountSql() {
        return SELECT_COUNT_SQL;
    }

    @Override
    protected String getDeleteSql() {
        return DELETE_SQL;
    }

    @Override
    protected String getDeleteInSql() {
        return DELETE_IN_SQL;
    }

    @Override
    protected String getUpdateSql() {
        return UPDATE_SQL;
    }

    private static Timestamp convertDobToTimestamp(ZonedDateTime dob) {
        return Timestamp.valueOf(dob.withZoneSameInstant(ZoneId.of("+0")).toLocalDateTime());
    }
}
```

```java
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


```

###CRUD Repository: Implementing a Custom Anootation
- in order to convey the to the CRUD repository the SQL statements that we need for the various functions we had to pepper this class with these extra getter methods here, but there are frameworks that allow us to do this in a better way
- give our CRUD repo the ability to get these sql queries without having to implement these abstract methods, and in doing so we will learn about the Java Reflection API. 
- the reflection API is pretty much only used by frameworks, but as we are creating this CRUD repository functionality we are basically creating a poor man's framework.
- modern frameworks use annotations: so we can create our own annotation, and in that annotation we can specify the sql that we want, and then our crud could dynamically at runtime learn the sql that it needs from the annotation and thereby we would no longer need these methods. 
- So we will create our own custom annotations, then use the Reflection API to find those annotations and read information that's in them, so that we don't need to implement those methods here. 
- do this we basicalyl create an interface, java basically uses the interface apparatus to implement annotations.
- we will want these annotations to be visible to our application code while we're running it. The easiest way to do that is to label this annotation with another annotation called 'Retention'
- and then inorder to be allowed to embed that SQL inside our usage of this annotation we will have to define an attribute insude of this annotation, without an attribute label @SQL(value =""asdasd")
- annotations are basically interfaces, and on interfaces you declare methods
- So if I declare a method on my interface named 'value' then that attribute will become basically the default attribute of the annotation such that I don't havfe to explicitly name that attribute value. 
- What does the Reflections API really do? The Reflections API allows us to write Java code that allows us to analyse our Java code.
- So we want to write some code in the CRUD Repository that is capable of seeing the Java code in the People Repository, and what we want it to do is to find a method called MapForSave and then we want this code to see if there is an @SQL annotation on this method map for save, and if there is grab this SQL string inside it, and use that SQL string. 
- So the Java Refelctions API allows us to analyse our own code in real time at runtime. 
- It's very meta. 
- So the way that we do that is to reference this class at runtime to get into the Reflections API. 
- So anytime you want to access a class of an object - this code will be running inside of an object - and so we can refer to the object using the 'this' keyword, and then if we want to get acess to the class that this objcet is part of then we can call 'this.getClass'
- so this getClass() method actually retruns something called a class (public final Class<?> getClass()) - so this is a Java representation of a class file. And so you can think o fit as the source code that we're looking at. In fact you can very much think of it as that, that's what it's essentially going to model. 
- And so when yout hink abotu what comprise a class, classes can have a name, fields, methods, so you can actually access all those same concepts through this 'Class' class. 
- We want to find a method on our class called MapForSave, because that method is going to have our new annotation on it. 
- And so there are methods on the Class class for finding methods: getMethod(), getMethods(), getDeclaredMethods()
- getDeclaredMethods returns an array of method objects. So we're going to use the streams api to convert an array of Objects into a stream of objects.
- whenever we talk about converting in the streams api we#re really saying use the map function.
- getAnnotation() takes a class as an input: enums, records, classes, itnerfaces are all classes
- So if this calss which will end up actually not being the CRUD repo but in our casr the PeopleRepo which is a subclass of the CRUD repo - has a method on it called MapForSave, and if that method has an annotation on it called 'SQL' and if that annotation has a value, get that! And then we just want to return that. But the findFirst returns an Optional, so in the case that findFirst doesnt return the annotation, we use orElse to fallback to the getSaveSql(), and that's actually fairly eloquent.

```java
// CRUD Repository
private String getSaveSqlByAnnotation() {
   return Arrays.stream(this.getClass().getDeclaredMethods()) // a stream of methods
           .filter(m -> "mapForSave".contentEquals(m.getName())) // a stream of filtered methods
           .map(m -> m.getAnnotation(SQL.class)) // a stream of SQL annotations
           .map(SQL::value)
           .findFirst().orElse(getSaveSql());

}

// PeopleRepository
@Override
@SQL(SAVE_PERSON_SQL)
void mapForSave(Person entity, PreparedStatement ps) throws SQLException {
    ps.setString(1, entity.getFirstName());
    ps.setString(2, entity.getLastName());
    ps.setTimestamp(3, convertDobToTimestamp(entity.getDob()));
}


//    @Override
//    protected String getSaveSql() {
//        return SAVE_PERSON_SQL;
//    }
```

- and now we don't need the getSaveSql() method to be abstract anymore, I don't want to force our subclasses to implement this method any more, so remove the abstract keyword, and that means I have to provide an implementation now.
- orElseGet takes a supplier so we can pass in a method reference to the getSaveSQL method

```java
    private String getSqlByAnnotation(String methodName, Supplier<String> sqlGetter) {
       return Arrays.stream(this.getClass().getDeclaredMethods()) // a stream of methods
               .filter(m -> methodName.contentEquals(m.getName())) // a stream of filtered methods
               .map(m -> m.getAnnotation(SQL.class)) // a stream of SQL annotations
               .map(SQL::value)
               .findFirst().orElseGet(sqlGetter);
    }

    public T save(T entity) {
        try {
            PreparedStatement ps = connection.prepareStatement(getSqlByAnnotation("mapForSave", this::getSaveSql), PreparedStatement.RETURN_GENERATED_KEYS);
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
```
- instead of searching for the method by methodName we could search by CRUD operation. So we could introduce another attribute on the @SQL annotation that lets us specify what the operation is: saving, updating, findAll
- you could even put that annotation up top on the class itself. As long as the annotation can be found according to the operation that you're trying to do everything is good.
- why using the orElseGet() with this method reference is better than just calling the getter method directly? because this::getUpdateSql is never even going to get evaluated unless it's actually needed, on the pther hand if this was actually not a method reference, that method would get called whether or not we needed it's value, so by using a method reference we are able to do what is called lazy loading, where you don;t actually do the processing required to make that call unless you actually need to. So we're just passing around a reference to the method vs calling the method. So that's whyt hat is of value.
- @Repeatable - we have to provide a reference to a container annotation that will hold our SQL annotation.
- if you want to be able to use the same annotation multiple times on a method then those annotations need to be wrapped inside of a container or parent annotation. We don't actually need to wrap them ourselves, Java will do that for us. But we still have to define a container annotation because whenw e use the Reflection API to find whatver methods we're interested in, the existing APIs won't let us say find all methods that have multiple @SQL annotations on them, so instead we have to say find all methods that have the container annotation, and then ocne we find that, then we can dig into that and get all the SQL annotations. But for the other methods that just ghave 1 SQL annotation those annotation will work just the same as it was.

```java
package com.grswebservices.peopledb.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Specify that this annotation can contain multiple @SQL annotation
 * by defining a value method that returns an array of SQL annotations.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface MultiSQL {
    SQL[] value();
}

```

```java
package com.grswebservices.peopledb.annotation;

import com.grswebservices.peopledb.model.CrudOperation;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@Repeatable(MultiSQL.class) // this annotation is repeatable
public @interface SQL {
    String value();
    CrudOperation operationType();
}

```
- now in addition we need to be able to search for SQL annotations that are found embedded inside our MultiSql annotation
- keep in mind that although we didn't explicity wrap these two SQL annotations inside a MultiSQL annotation Java will do that for us, by virtue of the fact that we have made these repeatable and we have multiple ones on this method
- so when we use the Reflection API to find these on this particular method, they won't show up on this method, isntesad this method will appear to only have one anootation on it, which will be the MultiSQL annotation, and then we will have to grab that multiSQL annotation and dig into it to get these out.
  - So it will look like this when we're programmatically looking for the SQL annotations


```java
    @Override
    @MultiSQL(
        @SQL(value = "SELECT ID, FIRST_NAME, LAST_NAME, DOB, SALARY FROM PEOPLE WHERE ID=?", operationType = CrudOperation.FIND_BY_ID)
        @SQL(value = FIND_BY_ID_SQL, operationType = CrudOperation.FIND_BY_ID)
    )
    Person extractEntityFromResultSet(ResultSet rs) throws SQLException {
        long personId = rs.getLong("ID");
        String firstName = rs.getString("FIRST_NAME");
        String lastName = rs.getString("LAST_NAME");
        ZonedDateTime dob = ZonedDateTime.of(rs.getTimestamp("DOB").toLocalDateTime(), ZoneId.of("+0"));
        BigDecimal salary = rs.getBigDecimal("SALARY");
        return new Person(personId, firstName, lastName, dob, salary);
    }
```

- So in addition to our existing code that says go find all the methods that have the SQL annotation on it, we also have to have code that says go fidn al the methods that have the multiSQL annotation on it, and then if and when we find that then we have to access that guys value to get at this array of SQL annotations, and then from that point it will be the same processing.
- But we don't explicitly need to wrap thos code inside a MultiSQL annotation.
- Stream.concat() concanetantes two streams together.
- And now the rest of this processing can take place on both of these streams.

```java
    private String getSqlByAnnotation(CrudOperation operationType, Supplier<String> sqlGetter) {
    Stream<SQL> multiSqlStream = Arrays.stream(this.getClass().getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(MultiSQL.class))
            .map(m -> m.getAnnotation(MultiSQL.class))
            .flatMap(msql -> Arrays.stream(msql.value()));

    Stream<SQL> sqlStream = Arrays.stream(this.getClass().getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(SQL.class))
            .map(m -> m.getAnnotation(SQL.class));

    return Stream.concat(multiSqlStream, sqlStream)
            .filter(a -> a.operationType().equals(operationType))
            .map(SQL::value)
            .findFirst().orElseGet(sqlGetter);
}
```

- And now we can get rid of tall of these methods:

```java
//@Override
//protected String getFindByIdSql() {
//    return FIND_BY_ID_SQL;
//}
//
//@Override
//protected String getFindAllSql() {
//    return FIND_ALL_SQL;
//}
//
//@Override
//protected String getCountSql() {
//    return SELECT_COUNT_SQL;
//}
//
//@Override
//protected String getDeleteSql() {
//    return DELETE_SQL;
//}
//
//@Override
//protected String getDeleteInSql() {
//    return DELETE_IN_SQL;
//}
```
- Satisfying to refactor and delete code, helps to feel on right path and doing a good job.
- Now the CRUD repositry is even bigger than this class was, however, where you'll start to see the value is when we create another repository.
- the CRUD repository from which we will now extend will now do most of the heavy lifting for us. And so now we will msotly just have to provide the SQL statements.

```java
package com.grswebservices.peopledb.repository;

import com.grswebservices.peopledb.annotation.MultiSQL;
import com.grswebservices.peopledb.annotation.SQL;
import com.grswebservices.peopledb.model.CrudOperation;
import com.grswebservices.peopledb.model.Person;

import java.math.BigDecimal;
import java.sql.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class PeopleRepository extends CRUDRepository<Person> {
    public static final String SAVE_PERSON_SQL = "INSERT INTO PEOPLE (FIRST_NAME, LAST_NAME, DOB) VALUES(?, ?, ?)";
    public static final String FIND_BY_ID_SQL = "SELECT ID, FIRST_NAME, LAST_NAME, DOB, SALARY FROM PEOPLE WHERE ID=?";
    public static final String FIND_ALL_SQL = "SELECT ID, FIRST_NAME, LAST_NAME, DOB, SALARY FROM PEOPLE";
    public static final String SELECT_COUNT_SQL = "SELECT COUNT(*) FROM PEOPLE";
    public static final String DELETE_SQL = "DELETE FROM PEOPLE WHERE ID=?";
    public static final String DELETE_IN_SQL = "DELETE FROM PEOPLE WHERE ID IN (:ids)";
    public static final String UPDATE_SQL = "UPDATE PEOPLE SET FIRST_NAME=?, LAST_NAME=?, DOB=?, SALARY=? WHERE ID=?";

    public PeopleRepository(Connection connection) {
        super(connection);
    }

    @Override
    @SQL(value = SAVE_PERSON_SQL, operationType = CrudOperation.SAVE)
    void mapForSave(Person entity, PreparedStatement ps) throws SQLException {
        ps.setString(1, entity.getFirstName());
        ps.setString(2, entity.getLastName());
        ps.setTimestamp(3, convertDobToTimestamp(entity.getDob()));
    }

    @Override
    @SQL(value = UPDATE_SQL, operationType = CrudOperation.UPDATE)
    void mapForUpdate(Person entity, PreparedStatement ps) throws SQLException {
        ps.setString(1, entity.getFirstName());
        ps.setString(2, entity.getLastName());
        ps.setTimestamp(3, convertDobToTimestamp(entity.getDob()));
        ps.setBigDecimal(4, entity.getSalary());
    }

    @Override
    @SQL(value = FIND_BY_ID_SQL, operationType = CrudOperation.FIND_BY_ID)
    @SQL(value = FIND_ALL_SQL, operationType = CrudOperation.FIND_ALL)
    @SQL(value = SELECT_COUNT_SQL, operationType = CrudOperation.COUNT)
    @SQL(value = DELETE_SQL, operationType = CrudOperation.DELETE_ONE)
    @SQL(value = DELETE_IN_SQL, operationType = CrudOperation.DELETE_MANY)
    Person extractEntityFromResultSet(ResultSet rs) throws SQLException {
        long personId = rs.getLong("ID");
        String firstName = rs.getString("FIRST_NAME");
        String lastName = rs.getString("LAST_NAME");
        ZonedDateTime dob = ZonedDateTime.of(rs.getTimestamp("DOB").toLocalDateTime(), ZoneId.of("+0"));
        BigDecimal salary = rs.getBigDecimal("SALARY");
        return new Person(personId, firstName, lastName, dob, salary);
    }

    private static Timestamp convertDobToTimestamp(ZonedDateTime dob) {
        return Timestamp.valueOf(dob.withZoneSameInstant(ZoneId.of("+0")).toLocalDateTime());
    }
}
```

```java
package com.grswebservices.peopledb.repository;

import com.grswebservices.peopledb.annotation.MultiSQL;
import com.grswebservices.peopledb.annotation.SQL;
import com.grswebservices.peopledb.exception.UnableToSaveException;
import com.grswebservices.peopledb.model.CrudOperation;
import com.grswebservices.peopledb.model.Entity;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

abstract public class CRUDRepository<T extends Entity> {

    // protected so that it can be seen by subclasses like PeopleRepository
    protected Connection connection;

    public CRUDRepository(Connection connection) {
        this.connection = connection;
    }

    public T save(T entity) {
        try {
            PreparedStatement ps = connection.prepareStatement(getSqlByAnnotation(CrudOperation.SAVE, this::getSaveSql), PreparedStatement.RETURN_GENERATED_KEYS);
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

    public Optional<T> findById(Long id) {
        T entity = null;

        try {
            PreparedStatement ps = connection.prepareStatement(getSqlByAnnotation(CrudOperation.FIND_BY_ID, this::getFindByIdSql));
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
            PreparedStatement ps = connection.prepareStatement(getSqlByAnnotation(CrudOperation.FIND_ALL, this::getFindAllSql));
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
            PreparedStatement ps = connection.prepareStatement(getSqlByAnnotation(CrudOperation.COUNT, this::getCountSql));
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
            PreparedStatement ps = connection.prepareStatement(getSqlByAnnotation(CrudOperation.DELETE_ONE, this::getDeleteSql));
            ps.setLong(1, entity.getId());
            int affectedRecordCount = ps.executeUpdate(); // can pass in sql directly into ps.executeUpdate() however it would not be as beneficial as defining our sql when we create our prepared statement because then that sql has the opportunity to be precompiled.
            System.out.println(affectedRecordCount);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // var args are basically a shorthand for passing in an array: People...entities = People[] entities - except that we don't have to create an array in the code that calls this method
    // so it makes it pretty easy to call this method and just pass in any arbitrary number of People objects
    @SafeVarargs
    public final void delete(T... entities) {
        try {
            Statement stmt = connection.createStatement();
            String ids = Arrays.stream(entities)
                    .map(T::getId) // convert stream of entities into a stream of ids
                    .map(String::valueOf) // convert stream of Long ids into a stream of Text ids - equivalent to String.valueOf(20L)
                    .collect(Collectors.joining(","));// collect all string ids together and put a comma between them = comma delimited.
            int affectedRecordCount = stmt.executeUpdate(getSqlByAnnotation(CrudOperation.DELETE_MANY, this::getDeleteInSql).replace(":ids", ids));// (:id)= "sql named parameter" - although h2 doesn't support this so this is a fake or poor man's version
            System.out.println(affectedRecordCount);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void update(T entity) {
        try {
            PreparedStatement ps = connection.prepareStatement(getSqlByAnnotation(CrudOperation.UPDATE, this::getUpdateSql));
            mapForUpdate(entity, ps);
            ps.setLong(5, entity.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String getSqlByAnnotation(CrudOperation operationType, Supplier<String> sqlGetter) {
        Stream<SQL> multiSqlStream = Arrays.stream(this.getClass().getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(MultiSQL.class))
                .map(m -> m.getAnnotation(MultiSQL.class))
                .flatMap(msql -> Arrays.stream(msql.value()));

        Stream<SQL> sqlStream = Arrays.stream(this.getClass().getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(SQL.class))
                .map(m -> m.getAnnotation(SQL.class));

        return Stream.concat(multiSqlStream, sqlStream)
                .filter(a -> a.operationType().equals(operationType))
                .map(SQL::value)
                .findFirst().orElseGet(sqlGetter);
    }

    protected String getSaveSql() {throw new RuntimeException("SQL not defined");}

    protected String getCountSql() {throw new RuntimeException("SQL not defined");};

    protected String getFindAllSql() {throw new RuntimeException("SQL not defined");};

    /**
     * @return Returns a String that represents the SQL needed to retrieve one entity.
     * The SQL must contain one SQL parameter, i.e. "?", that will bind to the
     * entity's ID.
     */
    protected String getFindByIdSql() {throw new RuntimeException("SQL not defined");};

    protected String getDeleteSql() {throw new RuntimeException("SQL not defined");};

    /**
     * @return Should return a SQL string like:
     * "DELETE FROM PEOPLE WHERE ID IN (:ids)"
     * Be sure to include the '(:ids)' named parameter and call it 'ids'
     */
    protected String getDeleteInSql() {throw new RuntimeException("SQL not defined");};

    protected String getUpdateSql() {throw new RuntimeException("SQL not defined");};

    abstract T extractEntityFromResultSet(ResultSet rs) throws SQLException;

    abstract void mapForSave(T entity, PreparedStatement ps) throws SQLException;

    abstract void mapForUpdate(T entity, PreparedStatement ps) throws SQLException;
}

```

---

### CRUD Repository: Custom ID Annotation
- I want to replace the wya that I am getting this id, currently i am just calling entity.getId(), that works the way you know it would, but i'd rather have some code that would dig into this entity using Relection API, and find the field that is annotated with the new Id annotation, and if it finds one, then this code shoudl get the value that is on that field and return that value. 
- so we're going to write another annotation finding method that can do the equivalent of what entity.getId() is doing but it will do it via annotation and therefore we will no longer require this class to implement that interface.
- replace the way that we're getting the id, currently we're using entity.getId9) but rather hsave code that digs into the entity using reflection api and find the field that is annotated with  new idf annotationa nd if it finds one then this code shoudl get the value that is on the field and return that value, so we will no longer require this class to implement the Entity nterface.
- remember in oop we're dealing with classes and objects - two sides of the same coin. F is a field of the class, but to read a vlaue from that field we have to pass in the object - the class doesnt have the value, the objcet has the value. Class is the blueprint while Object is liek the building. We3 can't store things in a blurpint but we can store things in a building (though static vairables do break this analogy).

```java
public void delete(T entity) {
        try {
            PreparedStatement ps = connection.prepareStatement(getSqlByAnnotation(CrudOperation.DELETE_ONE, this::getDeleteSql));
            ps.setLong(1, findIdByAnnotation(entity));
            int affectedRecordCount = ps.executeUpdate(); // can pass in sql directly into ps.executeUpdate() however it would not be as beneficial as defining our sql when we create our prepared statement because then that sql has the opportunity to be precompiled.
            System.out.println(affectedRecordCount);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

private Long findIdByAnnotation(T entity) {
    return Arrays.stream(entity.getClass().getDeclaredFields())
            .filter(f -> f.isAnnotationPresent(Id.class))
            .map(f -> {
                f.setAccessible(true); // tells Java that we want to override the stated level of access on that field
                Long id = null;
                try {
                    id = (long)f.get(entity);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
                return id;
            })
            .findFirst().orElseThrow(() -> new RuntimeException(("No ID annotated field found"))); // We should really be creating our own Runtime Exception
}
```

- this is how frameworks are actually working. Some of these frameworks have abnnotations exactly like this and they work similarly to this. Differences, our Id annotation is currently only working onthe fielc itself but more robust frameworks would also work if you put the annotation ona  method, ona setter or getter method.
- could have decided to generise id type to be any type not just long. Anothe rthing we wouldve done is added another type in is added a data type for the id where we specify the entity type pass those both i n - which is what Spring frameworks do.
- if its a string do this way, if long do this way ...

----

### Speeding up Queries with Indexes
- few techniques to vastly improve performance of database
- 2 seconds to fetch is really quite slow, and if multiple simulatenous users those seconds add up, and keep in mind process is unavailable to do other things for those 2 seconds.
- db had to scan over every sinlge row checking if id matched what we passed in the parameter, and because we chose a record that#s almost near the very bottom of the tablem the  meaning it had to do a full table scan to find that record
- how esle could a db find a record without scaninng every row
- hash, sets, hashmaps - key-value pair - hashmap can analyse key and generate hashcode, hashmap then uses hashcode to determine where in that table he entry should go, so the next time you try to retreive a value out of a hasmap using that key the hasmap doesnt have to iterate over every dingle entry int hat maop instead it takes that key you provided, it regenerates a hashcode for it and then it uses that hashcode to determine an index into a table where your record / entry exists, and then it can much more efficiently go straight to that entry, or at least it can get super close if there happen to be collisions where other entries have the same index, but it can cut down a lot on the amoutn of scanning that has to be done.
- databases can use very similar kinds of tricks and they are optimized to do so if you help them out. 
- Databases have the ability to generate what is called an 'index' - creation of idnex on a tbale can be thought of very similarly to a hashmap geneartting hashcodes that lead to indexes for records in a hashmaps internal table. Very similar concepts. Woi;dn't be surpised if a lot of databases used almost an exact same mechanims.
- key here is if you want to be abkle to look up records more quickly you need to consider using an index for a particular columnthat you#re going to be searching by.
- when you don't have indexes things are slow.
- when we create an inde xon a column in a db with 5mill records, the db has to go through the id col values to generate its index, so has to do some analysis.

```java
SELECT * FROM PEOPLE OFFSET 5000000 FETCH FIRST 1 ROW ONLY; -- skip 5 million records THEN FETCH FIRST 1 ROW ONLY
SELECT * FROM PEOPLE WHERE ID=5000521; -- 5 seconds BEFORE INDEX, 0.003s AFTER index

CREATE INDEX ID_IDX ON PEOPLE(ID);

```

- our index is specifically for the id column, so if we then try to query by email we will be back to a slow retrieval.
- some dbs accomodate complex indexes that are based on a combination of columns.
- if we want query by email to be fast we have to create another index specifically for the email column.
- indexes are invaluable
- so why not generate indexes on every single column every database table every time.
- with great power comes great responsibility.
- they come at a cost.
- key to figuring out is asking ourselves in what ways will our db be utilized - in what ways will our users likely to search for and query data.
- any columns users are likely to specifically search by, or grouping by, or sorting on, any of the sorts of columns where you will do these kind of perations you will generalyl want to have anindex genreated on them.
- the cost: when you have indexes generated on a column in a table and then you insert records into the table, inserts and updates can take longer now, because every single time you do an insert or update the db will certainly have to do its analysis 
- if you're working on a project where you know you're going to need to load an enormous number of records, and this is a brand new code and brand new databases, it may be worth considering that it may not be best to not have things like indexes generated on that table initially just so that you can quickly load up that data, you can then always come back later and generate thos eindexes and other optimization objects on the table afterwars.
- abcs of databases every developer needs to know, many beginners and intermediates get complaints from users that data takes too long to load yet the devs don't even know what an index is.
- it;s not a lot of effort to implement an index and yet huge performance improvements. 
- A good example of where a little bit of knowledge can go a really long way.
- 