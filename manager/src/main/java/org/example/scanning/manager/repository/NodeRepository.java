package org.example.scanning.manager.repository;

import org.example.scanning.manager.model.Node;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NodeRepository extends JpaRepository<Node, String> {

    List<Node> findAllByOrderByEnrolledAtAsc();
}
