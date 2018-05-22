package org.janelia.stitching;

import net.imglib2.concatenate.Concatenable;
import net.imglib2.concatenate.PreConcatenable;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineSet;
import net.imglib2.realtransform.AffineTransform;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;

public class TransformUtils
{
	public static < A extends AffineGet & AffineSet & Concatenable< AffineGet > & PreConcatenable< AffineGet > > A createTransform( final double[][] affine )
	{
		final A transform = createTransform( affine.length );
		transform.set( affine );
		return transform;
	}

	@SuppressWarnings( "unchecked" )
	public static < A extends AffineGet & AffineSet & Concatenable< AffineGet > & PreConcatenable< AffineGet > > A createTransform( final int dim )
	{
		switch ( dim )
		{
		case 2:
			return ( A ) new AffineTransform2D();
		case 3:
			return ( A ) new AffineTransform3D();
		default:
			return ( A ) new AffineTransform( dim );
		}
	}

	/**
	 * Returns the linear component of the given transform.
	 */
	public static < A extends AffineGet & AffineSet & Concatenable< AffineGet > & PreConcatenable< AffineGet > > A getLinearComponent( final AffineGet transform )
	{
		final double[][] linearComponentAffineMatrix = new double[ transform.numDimensions() ][ transform.numDimensions() + 1 ];
		for ( int dRow = 0; dRow < transform.numDimensions(); ++dRow )
			for ( int dCol = 0; dCol < transform.numDimensions(); ++dCol )
				linearComponentAffineMatrix[ dRow ][ dCol ] = transform.get( dRow, dCol );
		return createTransform( linearComponentAffineMatrix );
	}

	/**
	 * Returns the translational component of the given transform.
	 */
	public static < A extends AffineGet & AffineSet & Concatenable< AffineGet > & PreConcatenable< AffineGet > > A getTranslationalComponent( final AffineGet transform )
	{
		final double[][] translationalComponentAffineMatrix = new double[ transform.numDimensions() ][ transform.numDimensions() + 1 ];
		for ( int d = 0; d < transform.numDimensions(); ++d )
		{
			translationalComponentAffineMatrix[ d ][ transform.numDimensions() ] = transform.get( d, transform.numDimensions() );
			translationalComponentAffineMatrix[ d ][ d ] = 1;
		}
		return createTransform( translationalComponentAffineMatrix );
	}
}