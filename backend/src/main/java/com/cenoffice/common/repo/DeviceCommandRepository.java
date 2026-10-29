package com.cenoffice.common.repo;

import com.cenoffice.common.domain.DeviceCommand;
import com.cenoffice.common.domain.Enums;
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
