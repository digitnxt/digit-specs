package com.digit.boundary.validator;

import com.digit.boundary.model.BoundaryHierarchy;
import com.digit.boundary.model.BoundaryRelationship;
import com.digit.boundary.model.BoundaryTypeHierarchy;
import com.digit.boundary.repository.BoundaryHierarchyRepository;
import com.digit.boundary.constants.ErrorCodes;
import com.digit.boundary.repository.BoundaryRelationshipRepository;

import org.digit.tracer.model.CustomException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Boundary relationship validation. Mirrors Go validator.BoundaryRelationshipValidator. */
public final class BoundaryRelationshipValidator {
    private BoundaryRelationshipValidator() {}

    public static void validateRelationship(BoundaryRelationshipRepository repo,
                                            BoundaryHierarchyRepository hierarchyRepo,
                                            BoundaryRelationship rel) {
        validateCommon(repo, hierarchyRepo, rel, true);
    }

    public static void validateRelationshipUpdate(BoundaryRelationshipRepository repo,
                                                  BoundaryHierarchyRepository hierarchyRepo,
                                                  BoundaryRelationship rel) {
        validateCommon(repo, hierarchyRepo, rel, false);
    }

    private static void validateCommon(BoundaryRelationshipRepository repo,
                                       BoundaryHierarchyRepository hierarchyRepo,
                                       BoundaryRelationship rel, boolean checkDuplicate) {
        if (isEmpty(rel.getTenantId()) || isEmpty(rel.getCode()) || isEmpty(rel.getHierarchyType())) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Missing required fields in boundary relationship");
        }
        if (isEmpty(rel.getBoundaryType())) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Missing required field in boundary relationship: boundaryType");
        }
        if (repo == null) {
            return;
        }

        if (!repo.hierarchyExists(rel.getTenantId(), rel.getHierarchyType())) {
            throw new CustomException(ErrorCodes.NOT_FOUND, "Hierarchy with type '" + rel.getHierarchyType() + "' does not exist");
        }

        BoundaryHierarchy hierarchy = repo.getHierarchyDefinition(rel.getTenantId(), rel.getHierarchyType(), hierarchyRepo);
        if (hierarchy == null) {
            throw new CustomException(ErrorCodes.NOT_FOUND, "Hierarchy definition not found");
        }

        validateBoundaryTypeInHierarchy(rel.getBoundaryType(), hierarchy.getBoundaryHierarchy());

        if (!repo.boundaryExists(rel.getTenantId(), rel.getCode())) {
            throw new CustomException(ErrorCodes.NOT_FOUND, "Boundary with code '" + rel.getCode() + "' does not exist");
        }

        if (checkDuplicate) {
            if (repo.existsByCode(rel.getTenantId(), rel.getCode(), rel.getHierarchyType())) {
                throw new CustomException(ErrorCodes.CONFLICT, "Duplicate relationship code for tenant and hierarchy type");
            }
        }

        if (rel.getParent() != null && !rel.getParent().isEmpty()) {
            if (!repo.parentExists(rel.getTenantId(), rel.getParent(), rel.getHierarchyType())) {
                throw new CustomException(ErrorCodes.NOT_FOUND, "Parent relationship does not exist");
            }
            validateHierarchyOrder(repo, rel.getTenantId(), rel.getHierarchyType(),
                    rel.getBoundaryType(), rel.getParent(), hierarchy.getBoundaryHierarchy());
        }
    }

    private static void validateBoundaryTypeInHierarchy(String boundaryType, List<BoundaryTypeHierarchy> list) {
        // Go ranges over a nil slice as a no-op, falling through to the not-found error.
        if (list == null) {
            list = List.of();
        }
        for (BoundaryTypeHierarchy item : list) {
            if (boundaryType.equals(item.getBoundaryType())) {
                return;
            }
        }
        throw new CustomException(ErrorCodes.NOT_FOUND, "Boundary type '" + boundaryType + "' does not exist in hierarchy definition");
    }

    private static void validateHierarchyOrder(BoundaryRelationshipRepository repo, String tenantId,
                                               String hierarchyType, String childBoundaryType,
                                               String parentCode, List<BoundaryTypeHierarchy> list) {
        Map<String, Integer> levels = new HashMap<>();

        // Find the root (boundary type with no parent) and assign levels top-down.
        for (BoundaryTypeHierarchy item : list) {
            if (item.getParentBoundaryType() == null || item.getParentBoundaryType().isEmpty()) {
                assignLevels(item.getBoundaryType(), 0, list, levels);
                break;
            }
        }

        if (!levels.containsKey(childBoundaryType)) {
            throw new CustomException(ErrorCodes.NOT_FOUND, "Child boundary type not found in hierarchy");
        }
        int childLevel = levels.get(childBoundaryType);

        String expectedParentType = "";
        for (BoundaryTypeHierarchy item : list) {
            if (childBoundaryType.equals(item.getBoundaryType()) && item.getParentBoundaryType() != null) {
                expectedParentType = item.getParentBoundaryType();
                break;
            }
        }

        if (expectedParentType.isEmpty()) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Root level boundary type '" + childBoundaryType + "' cannot have a parent");
        }

        BoundaryRelationship parentRelationship = repo.getByCode(tenantId, parentCode, hierarchyType);
        if (parentRelationship == null) {
            throw new CustomException(ErrorCodes.NOT_FOUND, "Parent relationship not found");
        }

        if (!expectedParentType.equals(parentRelationship.getBoundaryType())) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,String.format(
                    "Invalid hierarchy order: parent boundary type '%s' does not match expected parent type '%s' for child type '%s'",
                    parentRelationship.getBoundaryType(), expectedParentType, childBoundaryType));
        }

        Integer parentLevel = levels.get(parentRelationship.getBoundaryType());
        if (parentLevel == null) {
            throw new CustomException(ErrorCodes.NOT_FOUND, "Parent boundary type not found in hierarchy");
        }

        if (parentLevel >= childLevel) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,String.format(
                    "Invalid hierarchy order: parent level (%d) must be higher than child level (%d)",
                    parentLevel, childLevel));
        }
    }

    private static void assignLevels(String boundaryType, int level,
                                     List<BoundaryTypeHierarchy> list, Map<String, Integer> levels) {
        levels.put(boundaryType, level);
        for (BoundaryTypeHierarchy item : list) {
            if (item.getParentBoundaryType() != null && item.getParentBoundaryType().equals(boundaryType)) {
                assignLevels(item.getBoundaryType(), level + 1, list, levels);
            }
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
