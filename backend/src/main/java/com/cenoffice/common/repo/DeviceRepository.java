package com.cenoffice.common.repo;

import com.cenoffice.common.domain.Device;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findByCode(String code);
}
