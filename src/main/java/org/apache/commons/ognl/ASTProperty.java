package org.apache.commons.ognl;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.commons.ognl.enhance.ExpressionCompiler;
import org.apache.commons.ognl.enhance.UnsupportedCompilationException;

import java.beans.IndexedPropertyDescriptor;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.Iterator;

/**
 * $Id$
 * @author Luke Blanshard (blanshlu@netscape.net)
 * @author Drew Davidson (drew@ognl.org)
 */
public class ASTProperty
    extends SimpleNode
    implements NodeType
{
    private boolean indexedAccess = false;

    private Class getterClass;

    private Class setterClass;

    public ASTProperty( int id )
    {
        super( id );
    }

    public void setIndexedAccess( boolean value )
    {
        indexedAccess = value;
    }

    /**
     * Returns true if this property is itself an index reference.
     * @return Returns true if this property is itself an index reference. 
     */
    public boolean isIndexedAccess()
    {
        return indexedAccess;
    }

    /**
     * Returns true if this property is described by an IndexedPropertyDescriptor and that if followed by an index
     * specifier it will call the index get/set methods rather than go through property accessors.
     * 
     * @param context The context
     * @param source The object source
     * @throws OgnlException if an error occurs
     * @return true, if this property is described by an IndexedPropertyDescriptor
     */
    public int getIndexedPropertyType( OgnlContext context, Object source )
        throws OgnlException
    {
        Class type = context.getCurrentType();
        Class prevType = context.getPreviousType();
        try
        {
            if ( !isIndexedAccess() )
            {
                Object property = getProperty( context, source );

                if ( property instanceof String )
                {
                    return OgnlRuntime.getIndexedPropertyType( 
                          context,
                          ( source == null ) ? null : OgnlRuntime.getCompiler( context ).getInterfaceClass( source.getClass() ),
                          (String) property );
                }
            }

            return OgnlRuntime.INDEXED_PROPERTY_NONE;
        }
        finally
        {
            context.setCurrentObject( source );
            context.setCurrentType( type );
            context.setPreviousType( prevType );
        }
    }

    public Object getProperty( OgnlContext context, Object source )
        throws OgnlException
    {
        return _children[0].getValue( context, context.getRoot() );
    }

    protected Object getValueBody( OgnlContext context, Object source )
        throws OgnlException
    {
        Object property = getProperty( context, source );

        Object result = OgnlRuntime.getProperty( context, source, property );

        if ( result == null )
        {
            result =
                OgnlRuntime.getNullHandler( OgnlRuntime.getTargetClass( source ) ).nullPropertyValue( context, source,
                                                                                                      property );
        }

        return result;
    }

    protected void setValueBody( OgnlContext context, Object target, Object value )
        throws OgnlException
    {
        OgnlRuntime.setProperty( context, target, getProperty( context, target ), value );
    }

    public boolean isNodeSimpleProperty( OgnlContext context )
        throws OgnlException
    {
        return ( _children != null ) && ( _children.length == 1 ) 
            && ( (SimpleNode) _children[0] ).isConstant( context );
    }

    public Class getGetterClass()
    {
        return getterClass;
    }

    public Class getSetterClass()
    {
        return setterClass;
    }

    public String toGetSourceString( OgnlContext context, Object target )
    {
        if ( context.getCurrentObject() == null )
        {
            throw new UnsupportedCompilationException( "Current target is null." );
        }
        String result = "";
        Method m = null;

        try
        {
            /*
             * System.out.println("astproperty is indexed? : " + isIndexedAccess() + " child: " +
             * _children[0].getClass().getName() + " target: " + target.getClass().getName() + " current object: " +
             * context.getCurrentObject().getClass().getName());
             */

            if ( isIndexedAccess() )
            {
                Object value = _children[0].getValue( context, context.getRoot() );

                if ( value == null || DynamicSubscript.class.isAssignableFrom( value.getClass() ) )
                {
                    throw new UnsupportedCompilationException(
                        "Value passed as indexed property was null or not supported." );
                }
                // Get root cast string if the child is a type that needs it (like a nested ASTProperty)

                String srcString = _children[0].toGetSourceString( context, context.getRoot() );
                srcString =
                    ExpressionCompiler.getRootExpression( _children[0], context.getRoot(), context ) + srcString;

                if ( ASTChain.class.isInstance( _children[0] ) )
                {
                    String cast = (String) context.remove( ExpressionCompiler.PRE_CAST );
                    if ( cast != null )
                    {
                        srcString = cast + srcString;
                    }
                }

                if ( ASTConst.class.isInstance( _children[0] ) 
                     && String.class.isInstance( context.getCurrentObject() ) )
                {
                    srcString = "\"" + srcString + "\"";
                }
                // System.out.println("indexed getting with child srcString: " + srcString + " value class: " +
                // value.getClass() + " and child: " + _children[0].getClass());

                if ( context.get( "_indexedMethod" ) != null )
                {
                    m = (Method) context.remove( "_indexedMethod" );
                    getterClass = m.getReturnType();

                    Object indexedValue = 
                        OgnlRuntime.callMethod( context, target, m.getName(), new Object[] { value } );

                    context.setCurrentType( getterClass );
                    context.setCurrentObject( indexedValue );
                    context.setCurrentAccessor( 
                        OgnlRuntime.getCompiler( context ).getSuperOrInterfaceClass( m, m.getDeclaringClass() ) );

                    return "." + m.getName() + "(" + srcString + ")";
                }
                else
                {
                    PropertyAccessor p = OgnlRuntime.getPropertyAccessor( target.getClass() );

                    // System.out.println("child value : " + _children[0].getValue(context, context.getCurrentObject())
                    // + " using propaccessor " + p.getClass().getName()
                    // + " and srcString " + srcString + " on target: " + target);

                    Object currObj = context.getCurrentObject();
                    Class currType = context.getCurrentType();
                    Class prevType = context.getPreviousType();

                    Object indexVal = p.getProperty( context, target, value );

                    // reset current object for accessor

                    context.setCurrentObject( currObj );
                    context.setCurrentType( currType );
                    context.setPreviousType( prevType );

                    /*
                     * System.out.println("astprop srcString: " + srcString + " from child class " +
                     * _children[0].getClass().getName() + " and indexVal " + indexVal + " propertyAccessor : " +
                     * p.getClass().getName() + " context obj " + context.getCurrentObject() +
                     * " context obj is array? : " + context.getCurrentObject().getClass().isArray() + " current type: "
                     * + context.getCurrentType());
                     */

                    if ( ASTConst.class.isInstance( _children[0] )
                        && Number.class.isInstance( context.getCurrentObject() ) )
                    {
                        context.setCurrentType( 
                            OgnlRuntime.getPrimitiveWrapperClass( context.getCurrentObject().getClass() ) );
                    }
                    result = p.getSourceAccessor( context, target, srcString );
                    getterClass = context.getCurrentType();
                    context.setCurrentObject( indexVal );

                    return result;
                }
            }

            String name = ( (ASTConst) _children[0] ).getValue().toString();

            target = getTarget( context, target, name );

            PropertyDescriptor pd = OgnlRuntime.getPropertyDescriptor( context.getCurrentObject().getClass(), name );

            if ( pd != null
                && pd.getReadMethod() != null
                && !context.getMemberAccess().isAccessible( context, context.getCurrentObject(), pd.getReadMethod(),
                                                            name ) )
            {
                throw new UnsupportedCompilationException( "Member access forbidden for property " + name
                    + " on class " + context.getCurrentObject().getClass() );
            }

            if ( this.getIndexedPropertyType( context, context.getCurrentObject() ) > 0 && pd != null )
            {
                // if an indexed method accessor need to use special property descriptors to find methods

                if ( pd instanceof IndexedPropertyDescriptor )
                {
                    m = ( (IndexedPropertyDescriptor) pd ).getIndexedReadMethod();
                }
                else
                {
                    if ( pd instanceof ObjectIndexedPropertyDescriptor )
                    {
                        m = ( (ObjectIndexedPropertyDescriptor) pd ).getIndexedReadMethod();
                    }
                    else
                    {
                        throw new OgnlException( "property '" + name + "' is not an indexed property" );
                    }
                }

                if ( _parent == null )
                {
                    // the above pd will be the wrong result sometimes, such as methods like getValue(int) vs String[]
                    // getValue()
                    m = OgnlRuntime.getReadMethod( context.getCurrentObject().getClass(), name );

                    result = m.getName() + "()";
                    getterClass = m.getReturnType();
                }
                else
                {
                    context.put( "_indexedMethod", m );
                }
            }
            else
            {

                /*
                 * System.out.println("astproperty trying to get " + name + " on object target: " +
                 * context.getCurrentObject().getClass().getName() + " current type " + context.getCurrentType() +
                 * " current accessor " + context.getCurrentAccessor() + " prev type " + context.getPreviousType() +
                 * " prev accessor " + context.getPreviousAccessor());
                 */

                PropertyAccessor pa = OgnlRuntime.getPropertyAccessor( context.getCurrentObject().getClass() );

                if ( context.getCurrentObject().getClass().isArray() )
                {
                    if ( pd == null )
                    {
                        pd = OgnlRuntime.getProperty( context.getCurrentObject().getClass(), name );

                        if ( pd != null && pd.getReadMethod() != null )
                        {
                            m = pd.getReadMethod();
                            result = pd.getName();
                        }
                        else
                        {
                            getterClass = int.class;
                            context.setCurrentAccessor( context.getCurrentObject().getClass() );
                            context.setCurrentType( int.class );
                            result = "." + name;
                        }
                    }
                }
                else
                {
                    if ( pd != null && pd.getReadMethod() != null )
                    {
                        m = pd.getReadMethod();
                        result = "." + m.getName() + "()";
                    }
                    else if ( pa != null )
                    {
                        Object currObj = context.getCurrentObject();
                        Class currType = context.getCurrentType();
                        Class prevType = context.getPreviousType();

                        String srcString = _children[0].toGetSourceString( context, context.getRoot() );

                        if ( ASTConst.class.isInstance( _children[0] )
                            && String.class.isInstance( context.getCurrentObject() ) ) 
                        {
                            srcString = "\"" + srcString + "\"";
                        }
                        context.setCurrentObject( currObj );
                        context.setCurrentType( currType );
                        context.setPreviousType( prevType );

                        result = pa.getSourceAccessor( context, context.getCurrentObject(), srcString );

                        getterClass = context.getCurrentType();
                    }
                }
            }

        }
        catch ( Throwable t )
        {
            throw OgnlOps.castToRuntime( t );
        }

        // set known property types for NodeType interface when possible

        if ( m != null )
        {
            getterClass = m.getReturnType();

            context.setCurrentType( m.getReturnType() );
            context.setCurrentAccessor( 
                OgnlRuntime.getCompiler( context ).getSuperOrInterfaceClass( m, m.getDeclaringClass() ) );
        }

        context.setCurrentObject( target );

        return result;
    }

    Object getTarget( OgnlContext context, Object target, String name )
        throws OgnlException
    {
        Class<? extends Object> clazz = context.getCurrentObject().getClass();
        if ( !Iterator.class.isAssignableFrom(clazz)
            || ( Iterator.class.isAssignableFrom(clazz)
                 && name.indexOf( "next" ) < 0 ) )
        {
            Object currObj = target;

            try
            {
                target = getValue( context, context.getCurrentObject() );
            }
            catch ( NoSuchPropertyException e )
            {
                try
                {
                    target = getValue( context, context.getRoot() );
                }
                catch ( NoSuchPropertyException ex )
                {
                    // ignore
                }
            }
            finally
            {
                context.setCurrentObject( currObj );
            }
        }
        return target;
    }

    Method getIndexedWriteMethod( PropertyDescriptor pd )
    {
        if ( IndexedPropertyDescriptor.class.isInstance( pd ) )
        {
            return ( (IndexedPropertyDescriptor) pd ).getIndexedWriteMethod();
        }
        else if ( ObjectIndexedPropertyDescriptor.class.isInstance( pd ) )
        {
            return ( (ObjectIndexedPropertyDescriptor) pd ).getIndexedWriteMethod();
        }

        return null;
    }

    public String toSetSourceString( OgnlContext context, Object target )
    {
        String result = "";
        Method m = null;

        if ( context.getCurrentObject() == null )
        {
            throw new UnsupportedCompilationException( "Current target is null." );
        }
        /*
         * System.out.println("astproperty(setter) is indexed? : " + isIndexedAccess() + " child: " +
         * _children[0].getClass().getName() + " target: " + target.getClass().getName() + " children length: " +
         * _children.length);
         */

        try
        {

            if ( isIndexedAccess() )
            {
                Object value = _children[0].getValue( context, context.getRoot() );

                if ( value == null )
                {
                    throw new UnsupportedCompilationException(
                        "Value passed as indexed property is null, can't enhance statement to bytecode." );
                }
                
                String srcString = _children[0].toGetSourceString( context, context.getRoot() );
                srcString =
                    ExpressionCompiler.getRootExpression( _children[0], context.getRoot(), context ) + srcString;

                if ( ASTChain.class.isInstance( _children[0] ) )
                {
                    String cast = (String) context.remove( ExpressionCompiler.PRE_CAST );
                    if ( cast != null )
                    {
                        srcString = cast + srcString;
                    }
                }

                if ( ASTConst.class.isInstance( _children[0] ) 
                     && String.class.isInstance( context.getCurrentObject() ) )
                {
                    srcString = "\"" + srcString + "\"";
                }

                // System.out.println("astproperty setter using indexed value " + value + " and srcString: " +
                // srcString);

                if ( context.get( "_indexedMethod" ) != null )
                {
                    m = (Method) context.remove( "_indexedMethod" );
                    PropertyDescriptor pd = (PropertyDescriptor) context.remove( "_indexedDescriptor" );

                    boolean lastChild = lastChild( context );
                    if ( lastChild )
                    {
                        m = getIndexedWriteMethod( pd );

                        if ( m == null )
                        {
                            throw new UnsupportedCompilationException(
                                "Indexed property has no corresponding write method." );
                        }
                    }

                    setterClass = m.getParameterTypes()[0];

                    Object indexedValue = null;
                    if ( !lastChild )
                    {
                        indexedValue = OgnlRuntime.callMethod( context, target, m.getName(), new Object[] { value } );
                    }
                    context.setCurrentType( setterClass );
                    context.setCurrentAccessor( 
                        OgnlRuntime.getCompiler( context ).getSuperOrInterfaceClass( m, m.getDeclaringClass() ) );

                    if ( !lastChild )
                    {
                        context.setCurrentObject( indexedValue );
                        return "." + m.getName() + "(" + srcString + ")";
                    }
                    else
                    {
                        return "." + m.getName() + "(" + srcString + ", $3)";
                    }
                }
                else
                {
                    PropertyAccessor p = OgnlRuntime.getPropertyAccessor( target.getClass() );

                    Object currObj = context.getCurrentObject();
                    Class currType = context.getCurrentType();
                    Class prevType = context.getPreviousType();

                    Object indexVal = p.getProperty( context, target, value );

                    // reset current object for accessor

                    context.setCurrentObject( currObj );
                    context.setCurrentType( currType );
                    context.setPreviousType( prevType );

                    if ( ASTConst.class.isInstance( _children[0] )
                        && Number.class.isInstance( context.getCurrentObject() ) )
                    {
                        context.setCurrentType( 
                            OgnlRuntime.getPrimitiveWrapperClass( context.getCurrentObject().getClass() ) );
                    }
                    result =
                        lastChild( context ) ? p.getSourceSetter( context, target, srcString )
                                        : p.getSourceAccessor( context, target, srcString );

                    /*
                     * System.out.println("ASTProperty using propertyaccessor and isLastChild? " + lastChild(context) +
                     * " generated source of: " + result + " using accessor class: " + p.getClass().getName());
                     */

                    // result = p.getSourceAccessor(context, target, srcString);
                    getterClass = context.getCurrentType();
                    context.setCurrentObject( indexVal );

                    /*
                     * PropertyAccessor p = OgnlRuntime.getPropertyAccessor(target.getClass()); if
                     * (ASTConst.class.isInstance(_children[0]) && Number.class.isInstance(context.getCurrentObject()))
                     * {
                     * context.setCurrentType(OgnlRuntime.getPrimitiveWrapperClass(context.getCurrentObject().getClass(
                     * ))); } result = p.getSourceSetter(context, target, srcString); context.setCurrentObject(value);
                     * context.setCurrentType(getterClass);
                     */
                    return result;
                }
            }

            String name = ( (ASTConst) _children[0] ).getValue().toString();

            // System.out.println(" astprop(setter) : trying to set " + name + " on object target " +
            // context.getCurrentObject().getClass().getName());

            target = getTarget( context, target, name );

            PropertyDescriptor pd =
                OgnlRuntime.getPropertyDescriptor( 
                    OgnlRuntime.getCompiler( context ).getInterfaceClass(
                        context.getCurrentObject().getClass() ), name );

            if ( pd != null )
            {
                Method pdMethod = lastChild( context ) ? pd.getWriteMethod() : pd.getReadMethod();

                if ( pdMethod != null
                    && !context.getMemberAccess().isAccessible( context, context.getCurrentObject(), pdMethod, name ) )
                {
                    throw new UnsupportedCompilationException( "Member access forbidden for property " + name
                        + " on class " + context.getCurrentObject().getClass() );
                }
            }

            if ( pd != null && this.getIndexedPropertyType( context, context.getCurrentObject() ) > 0 )
            {
                // if an indexed method accessor need to use special property descriptors to find methods

                if ( pd instanceof IndexedPropertyDescriptor )
                {
                    IndexedPropertyDescriptor ipd = (IndexedPropertyDescriptor) pd;
                    m = lastChild( context ) ? ipd.getIndexedWriteMethod() : ipd.getIndexedReadMethod();
                }
                else
                {
                    if ( pd instanceof ObjectIndexedPropertyDescriptor )
                    {
                        ObjectIndexedPropertyDescriptor opd = (ObjectIndexedPropertyDescriptor) pd;

                        m = lastChild( context ) ? opd.getIndexedWriteMethod() : opd.getIndexedReadMethod();
                    }
                    else
                    {
                        throw new OgnlException( "property '" + name + "' is not an indexed property" );
                    }
                }

                if ( _parent == null )
                {
                    // the above pd will be the wrong result sometimes, such as methods like getValue(int) vs String[]
                    // getValue()

                    m = OgnlRuntime.getWriteMethod( context.getCurrentObject().getClass(), name );
                    Class parm = m.getParameterTypes()[0];
                    String cast = parm.isArray() ? ExpressionCompiler.getCastString( parm ) : parm.getName();

                    result = m.getName() + "((" + cast + ")$3)";
                    setterClass = parm;
                }
                else
                {
                    context.put( "_indexedMethod", m );
                    context.put( "_indexedDescriptor", pd );
                }

            }
            else
            {
                PropertyAccessor pa = OgnlRuntime.getPropertyAccessor( context.getCurrentObject().getClass() );

                /*
                 * System.out.println("astproperty trying to set " + name + " on object target: " +
                 * context.getCurrentObject().getClass().getName() + " using propertyaccessor type: " + pa);
                 */

                if ( target != null )
                {
                    setterClass = target.getClass();
                }
                if ( _parent != null && pd != null && pa == null )
                {
                    m = pd.getReadMethod();
                    result = m.getName() + "()";
                }
                else
                {
                    if ( context.getCurrentObject().getClass().isArray() )
                    {
                        result = "";
                    }
                    else if ( pa != null )
                    {
                        Object currObj = context.getCurrentObject();
                        // Class currType = context.getCurrentType();
                        // Class prevType = context.getPreviousType();

                        String srcString = _children[0].toGetSourceString( context, context.getRoot() );

                        if ( ASTConst.class.isInstance( _children[0] )
                            && String.class.isInstance( context.getCurrentObject() ) )
                        {
                            srcString = "\"" + srcString + "\"";
                        }

                        context.setCurrentObject( currObj );
                        // context.setCurrentType(currType);
                        // context.setPreviousType(prevType);

                        if ( !lastChild( context ) )
                        {
                            result = pa.getSourceAccessor( context, context.getCurrentObject(), srcString );
                        }
                        else
                        {
                            result = pa.getSourceSetter( context, context.getCurrentObject(), srcString );
                        }

                        getterClass = context.getCurrentType();
                    }
                }
            }

        }
        catch ( Throwable t )
        {
            throw OgnlOps.castToRuntime( t );
        }

        context.setCurrentObject( target );

        if ( m != null )
        {
            context.setCurrentType( m.getReturnType() );
            context.setCurrentAccessor( 
                OgnlRuntime.getCompiler( context ).getSuperOrInterfaceClass( m, m.getDeclaringClass() ) );
        }

        return result;
    }
    
    public <R, P> R accept( NodeVisitor<? extends R, ? super P> visitor, P data )
        throws OgnlException
    {
        return visitor.visit( this, data );
    }
}
