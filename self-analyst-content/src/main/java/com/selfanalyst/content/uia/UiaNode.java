package com.selfanalyst.content.uia;

import java.util.ArrayList;
import java.util.List;

public record UiaNode(
        String name,
        String value,
        int controlType,
        String className,
        double[] bounds,
        boolean isPassword,
        List<UiaNode> children) {

    public UiaNode {
        if (children == null) children = List.of();
    }

    public static UiaNode create(String name, String value, int controlType,
                                  String className, double[] bounds, boolean isPassword) {
        return new UiaNode(name, value, controlType, className, bounds, isPassword, new ArrayList<>());
    }

    public void addChild(UiaNode child) {
        children.add(child);
    }
}
