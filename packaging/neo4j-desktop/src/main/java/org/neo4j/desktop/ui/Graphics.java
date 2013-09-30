/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.desktop.ui;

import java.awt.Image;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

import static java.lang.String.format;

public final class Graphics
{
    public static final String LOGO_PATTERN = "/neo4j-icon-%d.png";
    public static final String LOGO_32 = "/neo4j-icon-32.png";

    public static final String SYSTEM_TRAY_ICON = "/neo4j-systray-16.png";
    public static final int MIN_SIZE = 16;
    public static final int MAX_SIZE = 256;

    private Graphics()
    {
        throw new UnsupportedOperationException();
    }

    static List<Image> loadIcons()
    {
        List<Image> icons = new ArrayList<Image>();
        for ( int i = MIN_SIZE; i <= MAX_SIZE; i *= 2 )
        {
            Image image = loadImage( format( LOGO_PATTERN, i ) );
            if ( null != image )
            {
                icons.add( image );
            }
        }
        return icons;
    }

    static Image loadImage( String resource )
    {
        try
        {
            return ImageIO.read( Components.class.getResource( resource ) );
        }
        catch ( IOException e )
        {
            throw new IllegalStateException( e );
        }
    }
}
