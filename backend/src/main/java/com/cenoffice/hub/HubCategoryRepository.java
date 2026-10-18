package com.cenoffice.hub;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubCategoryRepository extends JpaRepository<HubCategory, String> {

}
