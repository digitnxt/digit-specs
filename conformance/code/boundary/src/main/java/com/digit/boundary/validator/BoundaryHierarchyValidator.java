package com.digit.boundary.validator;

import com.digit.boundary.constants.ErrorCodes;
import com.digit.boundary.model.BoundaryHierarchy;
import com.digit.boundary.model.BoundaryTypeHierarchy;
import com.digit.boundary.repository.BoundaryHierarchyRepository;

import org.digit.tracer.model.CustomException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Boundary hierarchy validation. Mirrors Go validator.BoundaryHierarchyValidator. */
public final class BoundaryHierarchyValidator {
    private BoundaryHierarchyValidator() {}

    public static void validateHierarchy(BoundaryHierarchyRepository repo, BoundaryHierarchy hierarchy) {
        if (isEmpty(hierarchy.getTenantId()) || isEmpty(hierarchy.getHierarchyType())) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Missing required fields in boundary hierarchy");
        }
        validateHierarchyStructure(hierarchy.getBoundaryHierarchy());
        if (repo != null) {
            if (repo.existsByType(hierarchy.getTenantId(), hierarchy.getHierarchyType())) {
                throw new CustomException(ErrorCodes.CONFLICT, "Duplicate hierarchy type for tenant");
            }
        }
    }

    public static void validateHierarchyStructure(List<BoundaryTypeHierarchy> hierarchyList) {
        if (hierarchyList == null || hierarchyList.isEmpty()) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Hierarchy must contain at least one boundary type");
        }

        Set<String> boundaryTypes = new HashSet<>();
        for (BoundaryTypeHierarchy item : hierarchyList) {
            if (isEmpty(item.getBoundaryType())) {
                throw new CustomException(ErrorCodes.BAD_REQUEST,"BoundaryType cannot be empty");
            }
            boundaryTypes.add(item.getBoundaryType());
        }

        Set<String> seen = new HashSet<>();
        for (BoundaryTypeHierarchy item : hierarchyList) {
            if (seen.contains(item.getBoundaryType())) {
                throw new CustomException(ErrorCodes.BAD_REQUEST,"Duplicate boundary type: " + item.getBoundaryType());
            }
            seen.add(item.getBoundaryType());
        }

        int rootCount = 0;
        for (BoundaryTypeHierarchy item : hierarchyList) {
            String parent = item.getParentBoundaryType();
            if (parent == null || parent.isEmpty()) {
                rootCount++;
            } else {
                if (!boundaryTypes.contains(parent)) {
                    throw new CustomException(ErrorCodes.BAD_REQUEST,"Parent boundary type '" + parent
                            + "' does not exist in hierarchy definition");
                }
                if (parent.equals(item.getBoundaryType())) {
                    throw new CustomException(ErrorCodes.BAD_REQUEST,"Boundary type cannot be its own parent: " + item.getBoundaryType());
                }
            }
        }

        if (rootCount == 0) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Hierarchy must have at least one root boundary type (with null parent)");
        }
        if (rootCount > 1) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Hierarchy can have only one root boundary type");
        }

        checkCircularDependencies(hierarchyList);
    }

    private static void checkCircularDependencies(List<BoundaryTypeHierarchy> hierarchyList) {
        Map<String, List<String>> children = new HashMap<>();
        for (BoundaryTypeHierarchy item : hierarchyList) {
            String parent = item.getParentBoundaryType();
            if (parent != null && !parent.isEmpty()) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(item.getBoundaryType());
            }
        }

        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();
        for (BoundaryTypeHierarchy item : hierarchyList) {
            if (!visited.contains(item.getBoundaryType())) {
                if (dfs(item.getBoundaryType(), children, visited, recStack)) {
                    throw new CustomException(ErrorCodes.BAD_REQUEST,"Circular dependency detected in hierarchy");
                }
            }
        }
    }

    private static boolean dfs(String node, Map<String, List<String>> children,
                               Set<String> visited, Set<String> recStack) {
        visited.add(node);
        recStack.add(node);
        for (String child : children.getOrDefault(node, List.of())) {
            if (!visited.contains(child)) {
                if (dfs(child, children, visited, recStack)) {
                    return true;
                }
            } else if (recStack.contains(child)) {
                return true;
            }
        }
        recStack.remove(node);
        return false;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
