package com.grswebservices.peopledb.model;

import java.time.ZonedDateTime;

public class Person {
    private Long id;

    public Person(String firstName, String lastName, ZonedDateTime dob) {
    }

    public Long getId() {
        return 1L;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
