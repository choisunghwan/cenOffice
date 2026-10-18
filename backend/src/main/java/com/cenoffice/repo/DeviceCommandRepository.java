package com.cenoffice.repo;

import com.cenoffice.domain.DeviceCommand;
import com.cenoffice.domain.Enums;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, UUID> {
    List<DeviceCommand> findByDeviceIdOrderByRequestedAtDesc(Long deviceId, Pageable page);

    boolean existsByDeviceIdAndStatusIn(Long deviceId, Collection<Enums.CommandStatus> statuses);

    List<DeviceCommand> findByStatusInAndRequestedAtBefore(Collection<Enums.CommandStatus> statuses, Instant before);
}
