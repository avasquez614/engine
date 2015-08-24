/*
 * Copyright (C) 2007-2015 Crafter Software Corporation.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.craftercms.engine.util;

import org.apache.commons.lang3.ArrayUtils;
import org.craftercms.engine.util.config.TargetingProperties;

/**
 * Utility methods for content targeting.
 *
 * @author avasquez
 */
public class TargetingUtils {

    private TargetingUtils() {
    }

    /**
     * Return the root folder of the specified targeted URL
     *
     * @param targetedUrl the targeted URL
     *
     * @return the root folder that matches the targeted URL
     */
    public static String getMatchingRootFolder(String targetedUrl) {
        String[] targetedRootFolders = TargetingProperties.getRootFolders();
        if (ArrayUtils.isNotEmpty(targetedRootFolders)) {
            for (String targetedRootFolder : targetedRootFolders) {
                if (targetedUrl.startsWith(targetedRootFolder)) {
                    return targetedRootFolder;
                }
            }
        }

        return null;
    }

}