/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package ai.djl.pytorch.engine;

import ai.djl.Device;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.NDUtils;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.index.NDIndexBooleans;
import ai.djl.ndarray.index.NDIndexElement;
import ai.djl.ndarray.index.NDIndexFullSlice;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.ndarray.types.SparseFormat;
import ai.djl.pytorch.jni.JniUtils;
import ai.djl.pytorch.jni.NativeResource;
import ai.djl.pytorch.jni.Pointer;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** {@code PtNDArray} is the PyTorch implementation of {@link NDArray}. */
public class PtNDArray extends NativeResource implements NDArray {

    private static final int MAX_SIZE = 100;
    private static final int MAX_DEPTH = 10;
    private static final int MAX_ROWS = 10;
    private static final int MAX_COLUMNS = 20;

    private String name;
    private Device device;
    private DataType dataType;
    private Shape shape;
    private SparseFormat sparseFormat;
    private PtNDManager manager;
    private PtNDArrayEx ptNDArrayEx;

    /**
     * Constructs an PtTorch from a native handle and metadata (internal. Use {@link NDManager}
     * instead).
     *
     * @param manager the manager to attach the new array to
     * @param handle the pointer to the native PtTorch memory
     * @param device the device the new array will be located on
     * @param shape the shape of the new array
     * @param dataType the dataType of the new array
     */
    PtNDArray(PtNDManager manager, Pointer handle, Device device, Shape shape, DataType dataType) {
        this(manager, handle);
        this.device = device;
        // shape check
        if (Arrays.stream(shape.getShape()).anyMatch(s -> s < 0)) {
            throw new IllegalArgumentException("The shape must be >= 0");
        }
        this.shape = shape;
        this.dataType = dataType;
    }

    /**
     * Constructs an PyTorch from a native handle (internal. Use {@link NDManager} instead).
     *
     * @param manager the manager to attach the new array to
     * @param handle the pointer to the native PyTorch memory
     */
    PtNDArray(PtNDManager manager, Pointer handle) {
        super(handle);
        this.manager = manager;
        this.ptNDArrayEx = new PtNDArrayEx(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDManager getManager() {
        return manager;
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return name;
    }

    /** {@inheritDoc} */
    @Override
    public void setName(String name) {
        this.name = name;
    }

    /** {@inheritDoc} */
    @Override
    public DataType getDataType() {
        if (dataType == null) {
            dataType = JniUtils.getDataType(this);
        }
        return dataType;
    }

    /** {@inheritDoc} */
    @Override
    public Device getDevice() {
        if (device == null) {
            device = JniUtils.getDevice(this);
        }
        return device;
    }

    /** {@inheritDoc} */
    @Override
    public Shape getShape() {
        if (shape == null) {
            shape = JniUtils.getShape(this);
        }
        return shape;
    }

    /** {@inheritDoc} */
    @Override
    public SparseFormat getSparseFormat() {
        if (sparseFormat == null) {
            sparseFormat = JniUtils.getSparseFormat(this);
        }
        return sparseFormat;
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray toDevice(Device device, boolean copy) {
        return JniUtils.to(this, getDataType(), device, copy);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray toType(DataType dataType, boolean copy) {
        return JniUtils.to(this, dataType, getDevice(), copy);
    }

    /** {@inheritDoc} */
    @Override
    public void attachGradient() {}

    /** {@inheritDoc} */
    @Override
    public void attachGradient(SparseFormat sparseFormat) {}

    /** {@inheritDoc} */
    @Override
    public PtNDArray getGradient() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public ByteBuffer toByteBuffer() {
        return JniUtils.getByteBuffer(this);
    }

    /** {@inheritDoc} */
    @Override
    public void set(Buffer data) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public void set(NDIndex index, NDArray value) {
        // use booleanMask for NDIndexBooleans case
        List<NDIndexElement> indices = index.getIndices();
        if (!indices.isEmpty() && indices.get(0) instanceof NDIndexBooleans) {
            if (indices.size() != 1) {
                throw new IllegalArgumentException(
                        "get() currently didn't support more that one boolean NDArray");
            }
            NDArray mask = ((NDIndexBooleans) indices.get(0)).getIndex();
            JniUtils.booleanMaskSet(this, (PtNDArray) value, (PtNDArray) mask);
        } else {
            NDIndexFullSlice fullSlice = index.getAsFullSlice(getShape()).orElse(null);
            if (fullSlice != null) {
                Stack<NDArray> prepareValue = new Stack<>();
                prepareValue.add(value);
                prepareValue.add(prepareValue.peek().toDevice(getDevice(), false));
                // Deal with the case target: (1, 10, 1), original (10)
                // try to find (10, 1) and reshape (10) to that
                Shape targetShape = fullSlice.getShape();
                while (targetShape.size() > value.size()) {
                    targetShape = targetShape.slice(1);
                }
                prepareValue.add(prepareValue.peek().reshape(targetShape));
                prepareValue.add(prepareValue.peek().broadcast(fullSlice.getShape()));
                JniUtils.indexSet(
                        this,
                        (PtNDArray) prepareValue.peek(),
                        fullSlice.getMin(),
                        fullSlice.getMax(),
                        fullSlice.getStep());
                for (NDArray toClean : prepareValue) {
                    if (toClean != value) {
                        toClean.close();
                    }
                }
            } else {
                throw new UnsupportedOperationException(
                        "set() currently supports all, fixed, and slices indices");
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void set(NDIndex index, Number value) {
        set(index, getManager().create(value));
    }

    /** {@inheritDoc} */
    @Override
    public void setScalar(NDIndex index, Number value) {
        set(index, value);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray get(NDIndex index) {
        // TODO find a better way to improve the speed
        if (isScalar()) {
            return (PtNDArray) duplicate();
        }
        // use booleanMask for NDIndexBooleans case
        List<NDIndexElement> indices = index.getIndices();
        if (!indices.isEmpty() && indices.get(0) instanceof NDIndexBooleans) {
            if (indices.size() != 1) {
                throw new IllegalArgumentException(
                        "get() currently didn't support more that one boolean NDArray");
            }
            return booleanMask(((NDIndexBooleans) indices.get(0)).getIndex(), 0);
        }

        NDIndexFullSlice fullSlice = index.getAsFullSlice(getShape()).orElse(null);
        if (fullSlice != null) {
            return JniUtils.index(this, fullSlice.getMin(), fullSlice.getMax(), fullSlice.getStep())
                    .squeeze(fullSlice.getToSqueeze().stream().mapToInt(i -> i).toArray());
        } else {
            throw new UnsupportedOperationException(
                    "get() currently supports all, fixed, and slices indices");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void copyTo(NDArray array) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public NDArray duplicate() {
        return JniUtils.clone(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray booleanMask(NDArray index, int axis) {
        Shape indexShape = index.getShape();
        if (indexShape.equals(getShape())) {
            // Result is flattened since shape is undetermined
            return JniUtils.booleanMask(this, (PtNDArray) index);
        } else if (indexShape.equals(getShape().slice(axis))) {
            // index will be broadcasted by default
            PtNDArray flattedResult = JniUtils.booleanMask(this, (PtNDArray) index);
            // Shape recovery
            Shape remainder = getShape().slice(0, axis);
            long selectedSize = flattedResult.getShape().size() / remainder.size();
            return flattedResult.reshape(remainder.addAll(new Shape(selectedSize)));
        } else {
            throw new UnsupportedOperationException(
                    "Not supported for shape not broadcastable "
                            + indexShape.toString()
                            + " vs "
                            + getShape().toString());
        }
    }

    /** {@inheritDoc} */
    @Override
    public NDArray sequenceMask(NDArray sequenceLength, float value) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /** {@inheritDoc} */
    @Override
    public NDArray sequenceMask(NDArray sequenceLength) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray zerosLike() {
        return JniUtils.zerosLike(this, getDataType(), getDevice(), SparseFormat.DENSE);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray onesLike() {
        return JniUtils.onesLike(this, getDataType(), getDevice(), SparseFormat.DENSE);
    }

    /** {@inheritDoc} */
    @Override
    public boolean contentEquals(Number number) {
        return JniUtils.contentEqual(this, (PtNDArray) manager.create(number));
    }

    /** {@inheritDoc} */
    @Override
    public boolean contentEquals(NDArray other) {
        if (other == null || (!shapeEquals(other))) {
            return false;
        }
        if (getDataType() != other.getDataType()) {
            return false;
        }
        return JniUtils.contentEqual(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray eq(Number other) {
        return eq(manager.create(other));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray eq(NDArray other) {
        return JniUtils.eq(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray neq(Number other) {
        return neq(manager.create(other));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray neq(NDArray other) {
        return JniUtils.neq(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray gt(Number other) {
        return gt(manager.create(other));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray gt(NDArray other) {
        return JniUtils.gt(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray gte(Number other) {
        return gte(manager.create(other));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray gte(NDArray other) {
        return JniUtils.gte(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray lt(Number other) {
        return lt(manager.create(other));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray lt(NDArray other) {
        return JniUtils.lt(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray lte(Number other) {
        return lte(manager.create(other));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray lte(NDArray other) {
        return JniUtils.lte(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray add(Number n) {
        return add(manager.create(n));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray add(NDArray other) {
        return JniUtils.add(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray sub(Number n) {
        return sub(manager.create(n));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray sub(NDArray other) {
        return JniUtils.sub(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray mul(Number n) {
        return mul(manager.create(n));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray mul(NDArray other) {
        return JniUtils.mul(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray div(Number n) {
        return div(manager.create(n));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray div(NDArray other) {
        return JniUtils.div(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray mod(Number n) {
        // TODO PyTorch known issue https://github.com/pytorch/pytorch/issues/24753
        // Current implementation only allow number have the same type fo tensor
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray mod(NDArray other) {
        return JniUtils.remainder(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray pow(Number n) {
        return pow(manager.create(n));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray pow(NDArray other) {
        return JniUtils.pow(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray addi(Number n) {
        return addi(manager.create(n));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray addi(NDArray other) {
        JniUtils.addi(this, (PtNDArray) other);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray subi(Number n) {
        return subi(manager.create(n));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray subi(NDArray other) {
        JniUtils.subi(this, (PtNDArray) other);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray muli(Number n) {
        return muli(manager.create(n));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray muli(NDArray other) {
        JniUtils.muli(this, (PtNDArray) other);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray divi(Number n) {
        return divi(manager.create(n));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray divi(NDArray other) {
        JniUtils.divi(this, (PtNDArray) other);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray modi(Number n) {
        return modi(manager.create(n));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray modi(NDArray other) {
        JniUtils.remainderi(this, (PtNDArray) other);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray powi(Number n) {
        return powi(manager.create(n));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray powi(NDArray other) {
        JniUtils.powi(this, (PtNDArray) other);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray maximum(Number n) {
        return maximum(manager.create(n));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray maximum(NDArray other) {
        if (!other.getDataType().equals(getDataType())) {
            throw new IllegalArgumentException(
                    "DataType mismatch, expected "
                            + getDataType()
                            + " Actual "
                            + other.getDataType());
        }
        return JniUtils.max(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray minimum(Number n) {
        return minimum(manager.create(n));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray minimum(NDArray other) {
        if (!other.getDataType().equals(getDataType())) {
            throw new IllegalArgumentException(
                    "DataType mismatch, expected "
                            + getDataType()
                            + " Actual "
                            + other.getDataType());
        }
        return JniUtils.min(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray all() {
        return JniUtils.all(toType(DataType.BOOLEAN, true));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray any() {
        return JniUtils.any(toType(DataType.BOOLEAN, true));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray none() {
        return JniUtils.none(toType(DataType.BOOLEAN, true));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray neg() {
        return JniUtils.neg(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray negi() {
        JniUtils.negi(this);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray abs() {
        return JniUtils.abs(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray square() {
        return JniUtils.square(this);
    }

    /** {@inheritDoc} */
    @Override
    public NDArray sqrt() {
        return JniUtils.sqrt(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray cbrt() {
        return JniUtils.pow(this, (PtNDArray) manager.create(1.0 / 3));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray floor() {
        return JniUtils.floor(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray ceil() {
        return JniUtils.ceil(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray round() {
        return JniUtils.round(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray trunc() {
        return JniUtils.trunc(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray exp() {
        return JniUtils.exp(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray log() {
        return JniUtils.log(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray log10() {
        return JniUtils.log10(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray log2() {
        return JniUtils.log2(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray sin() {
        return JniUtils.sin(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray cos() {
        return JniUtils.cos(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray tan() {
        return JniUtils.tan(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray asin() {
        return JniUtils.asin(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray acos() {
        return JniUtils.acos(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray atan() {
        return JniUtils.atan(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray sinh() {
        return JniUtils.sinh(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray cosh() {
        return JniUtils.cosh(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray tanh() {
        return JniUtils.tanh(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray asinh() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray acosh() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray atanh() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray toDegrees() {
        return mul(180.0).div(Math.PI);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray toRadians() {
        return mul(Math.PI).div(manager.create(180.0));
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray max() {
        return JniUtils.max(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray max(int[] axes, boolean keepDims) {
        if (axes.length > 1) {
            // TODO fix this
            throw new UnsupportedOperationException("Only 1 axis is support!");
        }
        return JniUtils.max(this, axes[0], keepDims);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray min() {
        return JniUtils.min(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray min(int[] axes, boolean keepDims) {
        if (axes.length > 1) {
            // TODO fix this
            throw new UnsupportedOperationException("Only 1 axis is support!");
        }
        return JniUtils.min(this, axes[0], keepDims);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray sum() {
        return JniUtils.sum(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray sum(int[] axes, boolean keepDims) {
        return JniUtils.sum(this, Arrays.stream(axes).mapToLong(i -> i).toArray(), keepDims);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray prod() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray prod(int[] axes, boolean keepDims) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray mean() {
        return JniUtils.mean(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray mean(int[] axes, boolean keepDims) {
        if (axes.length > 1) {
            // TODO fix this
            throw new UnsupportedOperationException("Only 1 axis is support!");
        }
        return JniUtils.mean(this, axes[0], keepDims);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray trace(int offset, int axis1, int axis2) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public NDList split(long sections, int axis) {
        long size = getShape().get(axis) / sections;
        return JniUtils.split(this, size, axis);
    }

    /** {@inheritDoc} */
    @Override
    public NDList split(long[] indices, int axis) {
        List<Long> ptIndex = new ArrayList<>();
        ptIndex.add(indices[0]);
        for (int i = 1; i < indices.length; i++) {
            ptIndex.add(indices[i] - indices[i - 1]);
        }
        ptIndex.add(size(axis) - indices[indices.length - 1]);
        return JniUtils.split(this, ptIndex.stream().mapToLong(i -> i).toArray(), axis);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray flatten() {
        return JniUtils.flatten(this, 0, -1);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray reshape(Shape shape) {
        return JniUtils.reshape(this, shape.getShape());
    }

    /** {@inheritDoc} */
    @Override
    public NDArray reshapeLike(NDArray array) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray expandDims(int axis) {
        return JniUtils.unsqueeze(this, axis);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray squeeze() {
        return JniUtils.squeeze(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray squeeze(int axis) {
        return JniUtils.squeeze(this, axis);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray squeeze(int[] axes) {
        if (isScalar()) {
            if (axes.length > 1 || axes[0] != 0) {
                throw new IllegalArgumentException(
                        "axis " + axes[0] + "is out of bounds for array of dimension 0");
            }
            return (PtNDArray) duplicate();
        }
        long[] shapeArr = getShape().getShape();
        List<Long> newShape = new ArrayList<>();
        Set<Integer> set =
                IntStream.of(axes).boxed().collect(Collectors.toCollection(HashSet::new));
        // check input
        for (int axis : axes) {
            if (shapeArr[axis] != 1) {
                throw new IllegalArgumentException(
                        "cannot select an axis to squeeze out which has size not equal to one");
            }
        }
        for (int i = 0; i < shapeArr.length; i++) {
            if (!set.contains(i)) {
                newShape.add(shapeArr[i]);
            }
        }
        return (PtNDArray) reshape(newShape.stream().mapToLong(i -> i).toArray());
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray logicalAnd(NDArray other) {
        return JniUtils.logicalAnd(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray logicalOr(NDArray other) {
        return JniUtils.logicalOr(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray logicalXor(NDArray other) {
        return JniUtils.logicalXor(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray logicalNot() {
        return JniUtils.logicalNot(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray argSort(int axis, boolean ascending) {
        if (!ascending) {
            throw new UnsupportedOperationException("Only support ascending!");
        }
        return JniUtils.argSort(this, axis, false);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray sort() {
        return sort(-1);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray sort(int axis) {
        return JniUtils.sort(this, axis, false);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray softmax(int[] axes, float temperature) {
        if (temperature != 1.0) {
            throw new UnsupportedOperationException("PyTorch softmax didn't support temperature");
        }
        if (axes.length > 1) {
            throw new UnsupportedOperationException(
                    "PyTorch softmax didn't support multiple dimension");
        }
        return JniUtils.softmax(this, axes[0], getDataType());
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray logSoftmax(int[] axes, float temperature) {
        if (temperature != 1.0) {
            throw new UnsupportedOperationException("PyTorch softmax didn't support temperature");
        }
        if (axes.length > 1) {
            throw new UnsupportedOperationException(
                    "PyTorch softmax didn't support multiple dimension");
        }
        return JniUtils.logSoftmax(this, axes[0], getDataType());
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray cumSum() {
        // TODO: change default behavior on cumSum
        if (isScalar()) {
            return (PtNDArray) reshape(1);
        }
        if (isEmpty()) {
            return (PtNDArray) reshape(0);
        }
        return cumSum(0);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray cumSum(int axis) {
        return JniUtils.cumSum(this, axis);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray isInfinite() {
        return JniUtils.isInf(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray isNaN() {
        return JniUtils.isNaN(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray createMask(NDIndex index) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray createMask(Predicate<Number> predicate) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray tile(long repeats) {
        // zero-dim
        if (isEmpty()) {
            return (PtNDArray) duplicate();
        }
        // scalar
        int dim = (isScalar()) ? 1 : getShape().dimension();
        long[] repeatsArray = new long[dim];
        Arrays.fill(repeatsArray, repeats);
        return tile(repeatsArray);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray tile(int axis, long repeats) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray tile(long[] repeats) {
        return JniUtils.tile(this, repeats);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray tile(Shape desiredShape) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray repeat(long repeats) {
        // zero-dim
        if (isEmpty()) {
            return (PtNDArray) duplicate();
        }
        // scalar
        int dim = (isScalar()) ? 1 : getShape().dimension();
        long[] repeatsArray = new long[dim];
        Arrays.fill(repeatsArray, repeats);
        return repeat(repeatsArray);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray repeat(int axis, long repeats) {
        return JniUtils.repeat(this, repeats, axis);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray repeat(long[] repeats) {
        PtNDArray temp = this;
        for (int dim = 0; dim < repeats.length; dim++) {
            temp = JniUtils.repeat(temp, repeats[dim], dim);
        }
        return temp;
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray repeat(Shape desiredShape) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray dot(NDArray other) {
        int selfDim = this.getShape().dimension();
        int otherDim = other.getShape().dimension();
        if (selfDim != otherDim || selfDim > 2) {
            throw new UnsupportedOperationException(
                    "Dimension mismatch or high dimensional dot operation is not supported. Please use .matMul instead.");
        }
        return JniUtils.dot(this, (PtNDArray) other);
    }

    @Override
    public NDArray matMul(NDArray other) {
        if (isScalar() || other.isScalar()) {
            throw new IllegalArgumentException("scalar is not allowed for matMul()");
        }
        return JniUtils.matmul(this, (PtNDArray) other);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray clip(Number min, Number max) {
        return JniUtils.clip(this, min, max);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray swapAxes(int axis1, int axis2) {
        return JniUtils.transpose(this, axis1, axis2);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray transpose() {
        int dim = getShape().dimension();
        int[] reversedShape = IntStream.range(0, dim).map(i -> dim - i - 1).toArray();
        return transpose(reversedShape);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray transpose(int... axes) {
        if (isScalar() && axes.length > 0) {
            throw new IllegalArgumentException("axes don't match NDArray");
        }
        return JniUtils.permute(this, Arrays.stream(axes).mapToLong(i -> i).toArray());
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray broadcast(Shape shape) {
        return JniUtils.broadcast(this, shape);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray argMax() {
        if (isEmpty()) {
            throw new IllegalArgumentException("attempt to get argMax of an empty NDArray");
        }
        return JniUtils.argMax(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray argMax(int axis) {
        if (isEmpty()) {
            Shape newShape = NDUtils.getShapeFromEmptyNDArrayForReductionOp(getShape(), axis);
            return (PtNDArray) manager.create(newShape, DataType.INT64);
        }
        // TODO pytorch bug: https://github.com/pytorch/pytorch/issues/37084
        if (isScalar()) {
            return (PtNDArray) manager.create(0L);
        }
        return JniUtils.argMax(this, axis, false);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray argMin() {
        if (isEmpty()) {
            throw new IllegalArgumentException("attempt to get argMin of an empty NDArray");
        }
        return JniUtils.argMin(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray argMin(int axis) {
        if (isEmpty()) {
            Shape newShape = NDUtils.getShapeFromEmptyNDArrayForReductionOp(getShape(), axis);
            return (PtNDArray) manager.create(newShape, DataType.INT64);
        }
        // TODO pytorch bug: https://github.com/pytorch/pytorch/issues/37084
        if (isScalar()) {
            return (PtNDArray) manager.create(0L);
        }
        return JniUtils.argMin(this, axis, false);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray percentile(Number percentile) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray percentile(Number percentile, int[] axes) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray median() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray median(int[] axes) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray toDense() {
        if (!isSparse()) {
            return (PtNDArray) duplicate();
        }
        return JniUtils.toDense(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray toSparse(SparseFormat fmt) {
        if (fmt == SparseFormat.DENSE) {
            throw new IllegalArgumentException("Default type is not allowed");
        }
        if (fmt != SparseFormat.COO) {
            throw new UnsupportedOperationException("Only COO sparse type supported for PyTorch");
        }
        if (fmt == getSparseFormat()) {
            return (PtNDArray) duplicate();
        }
        return JniUtils.toSparse(this);
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArray nonzero() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /** {@inheritDoc} */
    @Override
    public PtNDArrayEx getNDArrayInternal() {
        return ptNDArrayEx;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        if (isReleased()) {
            return "This array is already closed";
        }
        return toDebugString(MAX_SIZE, MAX_DEPTH, MAX_ROWS, MAX_COLUMNS);
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof PtNDArray) {
            return contentEquals((PtNDArray) obj);
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        Pointer pointer = handle.getAndSet(null);
        if (pointer != null) {
            JniUtils.deleteNdArray(pointer);
            manager.detach(getUid());
            manager = null;
        }
    }
}
