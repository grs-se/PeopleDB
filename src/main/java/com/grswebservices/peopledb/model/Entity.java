package com.grswebservices.peopledb.model;

public interface Entity {
    // note: ids keep incrementing, db keeps track of the last int, even if some records are deleted or not commited
    // this is how h2 and probably a lot of other dbs happen to work
    Long getId();

    void setId(Long id);
}
