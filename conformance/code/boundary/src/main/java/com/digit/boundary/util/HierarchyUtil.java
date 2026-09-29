package com.digit.boundary.util;

import com.digit.boundary.model.BoundaryHierarchy;
import com.digit.boundary.model.BoundaryHierarchySearchCriteria;
import com.digit.boundary.model.BoundaryTypeHierarchy;
import com.digit.boundary.repository.BoundaryHierarchyRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for working with boundary hierarchies. Mirrors Go internal/util/hierarchy_util.go:
 * resolves the ordered list of boundary types from root to leaf for a hierarchy definition.
 */
@Component
public class HierarchyUtil {

    private final BoundaryHierarchyRepository hierarchyRepo;

    public HierarchyUtil(BoundaryHierarchyRepository hierarchyRepo) {
        this.hierarchyRepo = hierarchyRepo;
    }

    /** Returns the ordered boundary types from root to leaf (e.g. [State, District, Block, Village]). */
    public List<String> getHierarchyOrder(String tenantId, String hierarchyType) {
        List<BoundaryHierarchy> hierarchies = hierarchyRepo.search(
                new BoundaryHierarchySearchCriteria(tenantId, hierarchyType));

        if (hierarchies.isEmpty()) {
            throw new IllegalStateException("hierarchy definition does not exist for tenantId="
                    + tenantId + ", hierarchyType=" + hierarchyType);
        }

        List<BoundaryTypeHierarchy> list = hierarchies.get(0).getBoundaryHierarchy();
        // Go ranges over a nil slice as a no-op, falling through to the no-root-node error.
        if (list == null) {
            list = List.of();
        }

        Map<String, String> parentToChild = new HashMap<>();
        String rootNode = "";
        for (BoundaryTypeHierarchy item : list) {
            String parent = item.getParentBoundaryType();
            if (parent == null || parent.isEmpty()) {
                rootNode = item.getBoundaryType();
            } else {
                parentToChild.put(parent, item.getBoundaryType());
            }
        }

        if (rootNode.isEmpty()) {
            throw new IllegalStateException("no root node found in hierarchy definition");
        }

        List<String> order = new ArrayList<>();
        order.add(rootNode);
        String current = rootNode;
        for (int i = 0; i < list.size() - 1; i++) {
            String child = parentToChild.get(current);
            if (child != null) {
                order.add(child);
                current = child;
            } else {
                break;
            }
        }
        return order;
    }
}
